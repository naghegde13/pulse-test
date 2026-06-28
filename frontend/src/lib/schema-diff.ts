import type { SchemaColumn } from "@/types";

export interface SchemaRenameDiff {
  from: SchemaColumn;
  to: SchemaColumn;
}

export interface SchemaRetypeDiff {
  before: SchemaColumn;
  after: SchemaColumn;
}

export interface SchemaDiff {
  input: SchemaColumn[];
  output: SchemaColumn[];
  added: SchemaColumn[];
  dropped: SchemaColumn[];
  renamed: SchemaRenameDiff[];
  retyped: SchemaRetypeDiff[];
}

export function computeSchemaDiff(inputRaw: SchemaColumn[], outputRaw: SchemaColumn[]): SchemaDiff {
  const input = Array.isArray(inputRaw) ? inputRaw : [];
  const output = Array.isArray(outputRaw) ? outputRaw : [];
  const outputByName = new Map(output.map((column) => [column.name, column]));
  const inputByName = new Map(input.map((column) => [column.name, column]));

  const retyped = input
    .map((before) => {
      const after = outputByName.get(before.name);
      if (!after || normalizeType(after.type) === normalizeType(before.type)) return null;
      return { before, after };
    })
    .filter((entry): entry is SchemaRetypeDiff => entry !== null);

  const unmatchedInput = input.filter((column) => !outputByName.has(column.name));
  const unmatchedOutput = output.filter((column) => !inputByName.has(column.name));
  const usedOutput = new Set<string>();
  const renamed: SchemaRenameDiff[] = [];

  for (const from of unmatchedInput) {
    const candidate = unmatchedOutput
      .filter((to) => !usedOutput.has(to.name))
      .map((to) => ({ to, score: renameScore(from, to) }))
      .sort((a, b) => b.score - a.score)[0];
    if (candidate && candidate.score >= 0.72) {
      renamed.push({ from, to: candidate.to });
      usedOutput.add(candidate.to.name);
    }
  }

  const renamedInputNames = new Set(renamed.map((entry) => entry.from.name));
  const renamedOutputNames = new Set(renamed.map((entry) => entry.to.name));

  return {
    input,
    output,
    added: unmatchedOutput.filter((column) => !renamedOutputNames.has(column.name)),
    dropped: unmatchedInput.filter((column) => !renamedInputNames.has(column.name)),
    renamed,
    retyped,
  };
}

function renameScore(from: SchemaColumn, to: SchemaColumn): number {
  let score = 0;
  if (normalizeType(from.type) === normalizeType(to.type)) score += 0.35;
  if (from.nullable === to.nullable) score += 0.1;
  if (from.lineage && from.lineage === to.lineage) score += 0.15;
  if (from.description && from.description === to.description) score += 0.2;
  score += nameSimilarity(from.name, to.name) * 0.45;
  return Math.min(score, 1);
}

function normalizeType(type: string | undefined): string {
  return (type ?? "").trim().toLowerCase();
}

function normalizeName(name: string): string {
  return name
    .toLowerCase()
    .replace(/^(src|source|raw|old|new)_/, "")
    .replace(/_(id|key|code)$/, "");
}

function nameSimilarity(a: string, b: string): number {
  const left = normalizeName(a);
  const right = normalizeName(b);
  if (left === right) return 1;
  if (left.includes(right) || right.includes(left)) return 0.82;
  const distance = levenshtein(left, right);
  return 1 - distance / Math.max(left.length, right.length, 1);
}

function levenshtein(a: string, b: string): number {
  const dp = Array.from({ length: a.length + 1 }, (_, i) =>
    Array.from({ length: b.length + 1 }, (_, j) => (i === 0 ? j : j === 0 ? i : 0))
  );
  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      dp[i][j] = Math.min(
        dp[i - 1][j] + 1,
        dp[i][j - 1] + 1,
        dp[i - 1][j - 1] + cost
      );
    }
  }
  return dp[a.length][b.length];
}
