"use client";

interface DagStep {
  name: string;
  description: string;
}

interface ChatDagProps {
  steps: DagStep[];
}

const CATEGORY_COLORS: Record<string, { bg: string; border: string; badge: string }> = {
  ingestion: { bg: "bg-blue-50", border: "border-blue-200", badge: "bg-blue-100 text-blue-700" },
  cleaning: { bg: "bg-amber-50", border: "border-amber-200", badge: "bg-amber-100 text-amber-700" },
  transform: { bg: "bg-amber-50", border: "border-amber-200", badge: "bg-amber-100 text-amber-700" },
  quality: { bg: "bg-green-50", border: "border-green-200", badge: "bg-green-100 text-green-700" },
  modeling: { bg: "bg-purple-50", border: "border-purple-200", badge: "bg-purple-100 text-purple-700" },
  orchestration: { bg: "bg-gray-50", border: "border-gray-200", badge: "bg-gray-100 text-gray-700" },
};

function classifyStep(name: string): string {
  const lower = name.toLowerCase();
  if (lower.includes("ingestion") || lower.includes("file") || lower.includes("jdbc") || lower.includes("kafka") || lower.includes("api")) return "ingestion";
  if (lower.includes("cleaning") || lower.includes("bronze")) return "cleaning";
  if (lower.includes("quality") || lower.includes("expect") || lower.includes("dq")) return "quality";
  if (lower.includes("scd") || lower.includes("dimension") || lower.includes("model") || lower.includes("snapshot") || lower.includes("aggregate") || lower.includes("mart") || lower.includes("reference")) return "modeling";
  if (lower.includes("schedule") || lower.includes("orchestrat") || lower.includes("retry")) return "orchestration";
  return "transform";
}

function categoryLabel(cat: string): string {
  return cat.charAt(0).toUpperCase() + cat.slice(1);
}

export function ChatDag({ steps }: ChatDagProps) {
  return (
    <div className="my-3 flex flex-col items-center gap-0">
      {steps.map((step, i) => {
        const cat = classifyStep(step.name);
        const colors = CATEGORY_COLORS[cat] || CATEGORY_COLORS.transform;
        return (
          <div key={i} className="flex flex-col items-center">
            {i > 0 && (
              <div className="flex flex-col items-center">
                <div className="w-px h-4 bg-border" />
                <div className="text-muted-foreground text-[10px]">▼</div>
                <div className="w-px h-1 bg-border" />
              </div>
            )}
            <div className={`w-full max-w-[340px] rounded-lg border ${colors.border} ${colors.bg} px-3 py-2.5`}>
              <div className="flex items-center gap-2 mb-1">
                <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded ${colors.badge}`}>
                  {categoryLabel(cat)}
                </span>
              </div>
              <div className="text-sm font-semibold">{step.name}</div>
              <div className="text-xs text-muted-foreground mt-0.5 leading-relaxed">{step.description}</div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

export function parsePipelineSteps(content: string): { steps: DagStep[]; beforeText: string; afterText: string } | null {
  // Try markdown table format first (Step | Blueprint | Name | Purpose)
  const tableResult = parsePipelineTable(content);
  if (tableResult) return tableResult;

  // Fallback: bold numbered list format (1. **Name**: description)
  const stepRegex = /\d+\.\s+\*\*([^*]+)\*\*[:\s]*([^]*?)(?=\d+\.\s+\*\*|$)/g;
  const firstStepMatch = /\d+\.\s+\*\*/.exec(content);
  if (!firstStepMatch) return null;

  const beforeText = content.slice(0, firstStepMatch.index).trim();
  const stepsRegion = content.slice(firstStepMatch.index);

  const steps: DagStep[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = stepRegex.exec(stepsRegion)) !== null) {
    const desc = match[2].trim().replace(/\s+/g, " ");
    steps.push({ name: match[1].trim(), description: desc });
    lastIndex = match.index + match[0].length;
  }

  if (steps.length < 2) return null;

  const afterText = stepsRegion.slice(lastIndex).trim();
  return { steps, beforeText, afterText };
}

function parsePipelineTable(content: string): { steps: DagStep[]; beforeText: string; afterText: string } | null {
  const lines = content.split("\n");

  // Find header row containing pipeline-like columns
  let headerIdx = -1;
  let nameCol = -1;
  let purposeCol = -1;
  let blueprintCol = -1;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line.startsWith("|") || !line.endsWith("|")) continue;
    const cells = line.split("|").map(c => c.trim()).filter(c => c !== "");
    const lower = cells.map(c => c.toLowerCase());

    // Detect pipeline plan headers
    const hasStep = lower.some(c => c === "step" || c === "#");
    const hasBlueprint = lower.some(c => c.includes("blueprint") || c.includes("action"));
    const hasName = lower.some(c => c === "name" || c === "instance");
    const hasPurpose = lower.some(c => c.includes("purpose") || c.includes("description") || c.includes("detail"));

    if (hasStep && (hasBlueprint || hasName) && (hasPurpose || hasName)) {
      headerIdx = i;
      nameCol = lower.findIndex(c => c === "name" || c === "instance");
      if (nameCol === -1) nameCol = lower.findIndex(c => c.includes("blueprint") || c.includes("action"));
      purposeCol = lower.findIndex(c => c.includes("purpose") || c.includes("description") || c.includes("detail"));
      blueprintCol = lower.findIndex(c => c.includes("blueprint") || c.includes("action"));
      break;
    }
  }

  if (headerIdx === -1) return null;

  // Skip separator row (|---|---|...)
  const dataStart = headerIdx + 2;
  if (dataStart >= lines.length) return null;

  const steps: DagStep[] = [];
  let tableEnd = dataStart;

  for (let i = dataStart; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line.startsWith("|") || !line.endsWith("|")) {
      tableEnd = i;
      break;
    }
    const cells = line.split("|").map(c => c.trim()).filter(c => c !== "");
    if (cells.length < 2) { tableEnd = i; break; }

    // Use name + blueprint for step name, purpose for description
    let stepName = "";
    if (nameCol >= 0 && nameCol < cells.length) stepName = cells[nameCol];
    if (!stepName && blueprintCol >= 0 && blueprintCol < cells.length) stepName = cells[blueprintCol];

    let desc = "";
    if (purposeCol >= 0 && purposeCol < cells.length) desc = cells[purposeCol];

    if (stepName) {
      steps.push({ name: stepName, description: desc });
    }
    tableEnd = i + 1;
  }

  if (steps.length < 2) return null;

  const beforeText = lines.slice(0, headerIdx).join("\n").trim();
  const afterText = lines.slice(tableEnd).join("\n").trim();
  return { steps, beforeText, afterText };
}
