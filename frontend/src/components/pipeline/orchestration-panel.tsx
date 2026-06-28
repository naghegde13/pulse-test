"use client";

import { useEffect, useMemo, useState, useTransition } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { api } from "@/lib/api";
import { ConfigureTransformDialog } from "./configure-transform-dialog";
import { CronBuilder } from "./cron-builder";
import type {
  Blueprint,
  BlueprintParamDefinition,
  CompositionView,
  PipelineVersion,
  SubPipelineInstance,
} from "@/types";

// W-7 (SPEC-construct-library.md §6): `ObjectStoreKeySensor` and
// `DatasetDependencySensor` are DEPRECATING — absorbed into FileArrivalSensor /
// ScheduleAndTriggers per #5 — so they are pruned from the live sensor set.
const GATE_FOUR_SENSOR_BLUEPRINT_KEYS = new Set([
  "FileArrivalSensor",
  "DatabaseReadinessSensor",
  "ExternalEventSensor",
]);

const SCHEDULE_POLICY_BLUEPRINT_KEY = "ScheduleAndTriggers";
const BACKFILL_POLICY_BLUEPRINT_KEY = "BackfillAndReplay";
const ROLLBACK_POLICY_BLUEPRINT_KEY = "RollbackOnFailure";
const COST_POLICY_BLUEPRINT_KEY = "CostMonitoringHook";

const SPECIAL_POLICY_BLUEPRINT_KEYS = new Set([
  SCHEDULE_POLICY_BLUEPRINT_KEY,
  BACKFILL_POLICY_BLUEPRINT_KEY,
  ROLLBACK_POLICY_BLUEPRINT_KEY,
  COST_POLICY_BLUEPRINT_KEY,
]);

type PolicyDraftMap = Record<string, Record<string, string>>;
type PolicyConfigMap = Record<string, Record<string, unknown>>;
type ScheduleMode = "cron" | "event" | "manual";
type RollbackTrigger = "deploy_failure" | "health_check_failure" | "manual";
type WorkflowIssue = {
  severity: "error" | "warning";
  title: string;
  detail: string;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function serializePolicyValue(
  definition: BlueprintParamDefinition,
  value: unknown
): string {
  if (value === undefined || value === null) return "";
  if (definition.type === "boolean") return String(Boolean(value));
  if (definition.type === "object" || definition.type === "object[]") {
    return typeof value === "string" ? value : JSON.stringify(value, null, 2);
  }
  return String(value);
}

function parsePolicyValue(
  definition: BlueprintParamDefinition,
  rawValue: string
): unknown {
  if (definition.type === "integer") {
    return Number.parseInt(rawValue, 10);
  }
  if (definition.type === "number") {
    return Number.parseFloat(rawValue);
  }
  if (definition.type === "boolean") {
    return rawValue === "true";
  }
  if (definition.type === "object" || definition.type === "object[]") {
    return JSON.parse(rawValue);
  }
  return rawValue;
}

function getPolicyBlueprintConfigs(version?: PipelineVersion | null): PolicyConfigMap {
  const raw = version?.metadata?.orchestrationPolicyBlueprints;
  if (!isRecord(raw)) {
    return {};
  }

  return Object.fromEntries(
    Object.entries(raw)
      .filter(([, value]) => isRecord(value))
      .map(([key, value]) => [key, value as Record<string, unknown>])
  ) as PolicyConfigMap;
}

function getPipelinePolicySnapshot(version?: PipelineVersion | null) {
  const raw = version?.metadata?.orchestrationPolicy;
  return isRecord(raw) ? raw : {};
}

function getNumericValue(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function getStringValue(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

function getBooleanValue(value: unknown, fallback = false): boolean {
  return typeof value === "boolean" ? value : fallback;
}

function buildPolicyDrafts(
  blueprints: Record<string, Blueprint>,
  version?: PipelineVersion | null
): PolicyDraftMap {
  const existingPolicies = getPolicyBlueprintConfigs(version);

  return Object.fromEntries(
    Object.values(blueprints)
      .filter(
        (blueprint) =>
          blueprint.category === "ORCHESTRATION" && blueprint.pipelineConfig
      )
      .map((blueprint) => {
        const currentValues = existingPolicies[blueprint.blueprintKey] ?? {};
        return [
          blueprint.blueprintKey,
          Object.fromEntries(
            blueprint.paramsSchema.map((definition) => [
              definition.name,
              serializePolicyValue(
                definition,
                currentValues[definition.name] ?? definition.default
              ),
            ])
          ),
        ];
      })
  );
}

function humanizeKey(value: string): string {
  return value
    .split("_")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function humanizeEnumValue(value: string): string {
  return humanizeKey(value).replace(/\bDq\b/g, "DQ");
}

function formatCurrency(value: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    maximumFractionDigits: value % 1 === 0 ? 0 : 2,
  }).format(value);
}

function buildScheduleSummary({
  scheduleType,
  scheduleCron,
  triggerDataset,
}: {
  scheduleType: ScheduleMode;
  scheduleCron: string;
  triggerDataset: string;
}) {
  if (scheduleType === "manual") return "Manual only";
  if (scheduleType === "event") {
    return triggerDataset.trim() ? `Event: ${triggerDataset.trim()}` : "Event trigger";
  }
  return scheduleCron.trim() || "@daily";
}

function inferLayerFromDatasetRef(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.toLowerCase();
  if (normalized.includes(".bronze.")) return "bronze";
  if (normalized.includes(".silver.")) return "silver";
  if (normalized.includes(".gold.")) return "gold";
  return null;
}

function formatLayerName(layer: string): string {
  return layer.charAt(0).toUpperCase() + layer.slice(1);
}

interface OrchestrationPanelProps {
  tenantId: string;
  pipelineId: string;
  versionId: string;
  editable: boolean;
  selectedVersion?: PipelineVersion | null;
}

export function OrchestrationPanel({
  tenantId,
  pipelineId,
  versionId,
  editable,
  selectedVersion,
}: OrchestrationPanelProps) {
  const [composition, setComposition] = useState<CompositionView | null>(null);
  const [blueprintLookup, setBlueprintLookup] = useState<Record<string, Blueprint>>(
    {}
  );
  const [selectedInstance, setSelectedInstance] =
    useState<SubPipelineInstance | null>(null);
  const [savedVersionOverride, setSavedVersionOverride] =
    useState<PipelineVersion | null>(null);
  const [scheduleCron, setScheduleCron] = useState("@daily");
  const [scheduleType, setScheduleType] = useState<ScheduleMode>("cron");
  const [triggerDataset, setTriggerDataset] = useState("");
  const [timezone, setTimezone] = useState("UTC");
  const [retryCount, setRetryCount] = useState(3);
  const [catchupEnabled, setCatchupEnabled] = useState(false);
  const [maxActiveRuns, setMaxActiveRuns] = useState<number>(1);
  const [dependsOnPast, setDependsOnPast] = useState(false);
  const [backfillStartDate, setBackfillStartDate] = useState("");
  const [backfillEndDate, setBackfillEndDate] = useState("");
  const [backfillParallelism, setBackfillParallelism] = useState(1);
  const [clearExistingBackfill, setClearExistingBackfill] = useState(false);
  const [rollbackTrigger, setRollbackTrigger] =
    useState<RollbackTrigger>("deploy_failure");
  const [keepFailedArtifacts, setKeepFailedArtifacts] = useState(true);
  const [budgetLimitDaily, setBudgetLimitDaily] = useState("");
  const [alertThresholdPercent, setAlertThresholdPercent] = useState(80);
  const [trackCompute, setTrackCompute] = useState(true);
  const [trackStorage, setTrackStorage] = useState(true);
  const [savingPolicy, setSavingPolicy] = useState(false);
  const [policyDrafts, setPolicyDrafts] = useState<PolicyDraftMap>({});
  const [, startTransition] = useTransition();

  const effectiveVersion = useMemo(() => {
    if (
      savedVersionOverride &&
      savedVersionOverride.id === selectedVersion?.id
    ) {
      return savedVersionOverride;
    }
    return selectedVersion ?? null;
  }, [savedVersionOverride, selectedVersion]);

  useEffect(() => {
    if (
      savedVersionOverride &&
      selectedVersion &&
      savedVersionOverride.id === selectedVersion.id &&
      savedVersionOverride.updatedAt !== selectedVersion.updatedAt
    ) {
      setSavedVersionOverride(null);
    }
    if (!selectedVersion) {
      setSavedVersionOverride(null);
    }
  }, [savedVersionOverride, selectedVersion]);

  useEffect(() => {
    startTransition(async () => {
      try {
        const [compositionData, blueprints] = await Promise.all([
          api.get<CompositionView>(`/api/v1/versions/${versionId}/composition`),
          api.get<Blueprint[]>("/api/v1/blueprints?includeDeferred=true"),
        ]);
        setComposition(compositionData);
        setBlueprintLookup(
          Object.fromEntries(
            blueprints.map((blueprint) => [blueprint.blueprintKey, blueprint])
          )
        );
      } catch {
        setComposition({ instances: [], wirings: [] });
        setBlueprintLookup({});
      }
    });
  }, [versionId]);

  useEffect(() => {
    const pipelinePolicy = getPipelinePolicySnapshot(effectiveVersion);
    const blueprintPolicies = getPolicyBlueprintConfigs(effectiveVersion);
    const schedulePolicy = blueprintPolicies[SCHEDULE_POLICY_BLUEPRINT_KEY] ?? {};
    const backfillPolicy = blueprintPolicies[BACKFILL_POLICY_BLUEPRINT_KEY] ?? {};
    const rollbackPolicy = blueprintPolicies[ROLLBACK_POLICY_BLUEPRINT_KEY] ?? {};
    const costPolicy = blueprintPolicies[COST_POLICY_BLUEPRINT_KEY] ?? {};

    const resolvedScheduleType = (
      getStringValue(schedulePolicy.schedule_type) ||
      (effectiveVersion?.scheduleCron ? "cron" : "manual")
    ) as ScheduleMode;

    setScheduleCron(
      getStringValue(
        effectiveVersion?.scheduleCron ??
          pipelinePolicy.scheduleCron ??
          schedulePolicy.cron_expression,
        "@daily"
      )
    );
    setScheduleType(
      ["cron", "event", "manual"].includes(resolvedScheduleType)
        ? resolvedScheduleType
        : "cron"
    );
    setTriggerDataset(getStringValue(schedulePolicy.trigger_dataset));
    setTimezone(getStringValue(schedulePolicy.timezone, "UTC"));
    setRetryCount(getNumericValue(schedulePolicy.retry_count, 3));
    setCatchupEnabled(
      getBooleanValue(
        effectiveVersion?.catchupEnabled ?? pipelinePolicy.catchupEnabled,
        false
      )
    );
    setMaxActiveRuns(
      getNumericValue(
        effectiveVersion?.maxActiveRuns ?? pipelinePolicy.maxActiveRuns,
        1
      )
    );
    setDependsOnPast(
      getBooleanValue(
        effectiveVersion?.dependsOnPast ?? pipelinePolicy.dependsOnPast,
        false
      )
    );
    setBackfillStartDate(getStringValue(backfillPolicy.start_date));
    setBackfillEndDate(getStringValue(backfillPolicy.end_date));
    setBackfillParallelism(getNumericValue(backfillPolicy.parallelism, 1));
    setClearExistingBackfill(
      getBooleanValue(backfillPolicy.clear_existing, false)
    );
    setRollbackTrigger(
      (["deploy_failure", "health_check_failure", "manual"] as const).includes(
        rollbackPolicy.rollback_trigger as RollbackTrigger
      )
        ? (rollbackPolicy.rollback_trigger as RollbackTrigger)
        : "deploy_failure"
    );
    setKeepFailedArtifacts(
      getBooleanValue(rollbackPolicy.keep_failed_artifacts, true)
    );
    setBudgetLimitDaily(
      costPolicy.budget_limit_daily === undefined ||
        costPolicy.budget_limit_daily === null
        ? ""
        : String(costPolicy.budget_limit_daily)
    );
    setAlertThresholdPercent(
      getNumericValue(costPolicy.alert_threshold_percent, 80)
    );
    setTrackCompute(getBooleanValue(costPolicy.track_compute, true));
    setTrackStorage(getBooleanValue(costPolicy.track_storage, true));
  }, [effectiveVersion]);

  useEffect(() => {
    setPolicyDrafts(buildPolicyDrafts(blueprintLookup, effectiveVersion));
  }, [blueprintLookup, effectiveVersion]);

  const orchestrationInstances = useMemo(
    () =>
      (composition?.instances ?? []).filter((instance) => {
        const blueprint = instance.blueprintKey
          ? blueprintLookup[instance.blueprintKey]
          : undefined;
        return blueprint?.category === "ORCHESTRATION";
      }),
    [blueprintLookup, composition?.instances]
  );

  const dataflowInstances = useMemo(
    () =>
      (composition?.instances ?? []).filter((instance) => {
        const blueprint = instance.blueprintKey
          ? blueprintLookup[instance.blueprintKey]
          : undefined;
        return blueprint?.category !== "ORCHESTRATION";
      }),
    [blueprintLookup, composition?.instances]
  );

  const orchestrationPolicies = useMemo(
    () =>
      Object.values(blueprintLookup).filter(
        (blueprint) =>
          blueprint.category === "ORCHESTRATION" &&
          blueprint.pipelineConfig === true
      ),
    [blueprintLookup]
  );

  const genericPolicies = useMemo(
    () =>
      orchestrationPolicies.filter(
        (blueprint) => !SPECIAL_POLICY_BLUEPRINT_KEYS.has(blueprint.blueprintKey)
      ),
    [orchestrationPolicies]
  );

  const sensorCount = useMemo(
    () =>
      orchestrationInstances.filter((instance) =>
        instance.blueprintKey
          ? GATE_FOUR_SENSOR_BLUEPRINT_KEYS.has(instance.blueprintKey)
          : false
      ).length,
    [orchestrationInstances]
  );

  const orchestrationScope = useMemo(() => {
    const layers = new Set<string>();
    const assetRefs = new Set<string>();

    for (const instance of dataflowInstances) {
      const blueprint = instance.blueprintKey
        ? blueprintLookup[instance.blueprintKey]
        : undefined;

      for (const layer of blueprint?.validLayers ?? []) {
        if (["bronze", "silver", "gold"].includes(layer)) {
          layers.add(layer);
        }
      }

      for (const dataset of [...instance.inputDatasets, ...instance.outputDatasets]) {
        if (isRecord(dataset) && typeof dataset.ref === "string") {
          assetRefs.add(dataset.ref);
          const inferredLayer = inferLayerFromDatasetRef(dataset.ref);
          if (inferredLayer) {
            layers.add(inferredLayer);
          }
        }
      }
    }

    return {
      layers: ["bronze", "silver", "gold"].filter((layer) => layers.has(layer)),
      assetRefs: Array.from(assetRefs),
    };
  }, [blueprintLookup, dataflowInstances]);

  const scheduleSummary = useMemo(
    () =>
      buildScheduleSummary({
        scheduleType,
        scheduleCron,
        triggerDataset,
      }),
    [scheduleCron, scheduleType, triggerDataset]
  );

  const backfillSummary = useMemo(() => {
    if (backfillStartDate && backfillEndDate) {
      return `${backfillStartDate} → ${backfillEndDate}`;
    }
    return catchupEnabled ? "Catchup enabled" : "On-demand only";
  }, [backfillEndDate, backfillStartDate, catchupEnabled]);

  const rollbackSummary = useMemo(
    () => humanizeEnumValue(rollbackTrigger),
    [rollbackTrigger]
  );

  const costSummary = useMemo(() => {
    if (!budgetLimitDaily.trim()) {
      return `Alert at ${alertThresholdPercent}%`;
    }
    const parsedBudget = Number.parseFloat(budgetLimitDaily);
    if (Number.isNaN(parsedBudget)) {
      return `Budget pending (${alertThresholdPercent}% alert)`;
    }
    return `${formatCurrency(parsedBudget)}/day @ ${alertThresholdPercent}%`;
  }, [alertThresholdPercent, budgetLimitDaily]);

  const workflowIssues = useMemo<WorkflowIssue[]>(() => {
    const issues: WorkflowIssue[] = [];
    const parsedBudget = budgetLimitDaily.trim()
      ? Number.parseFloat(budgetLimitDaily)
      : null;

    if (scheduleType === "event" && !triggerDataset.trim()) {
      issues.push({
        severity: "error",
        title: "Trigger dataset missing",
        detail: "Dataset-triggered orchestration needs a trigger dataset name.",
      });
    }

    if (scheduleType === "manual" && catchupEnabled) {
      issues.push({
        severity: "error",
        title: "Catchup conflicts with manual-only runs",
        detail:
          "Catchup only makes sense for scheduled cadences. Disable catchup or switch back to a cron schedule.",
      });
    }

    if (retryCount < 0) {
      issues.push({
        severity: "error",
        title: "Retry count cannot be negative",
        detail: "Set retry count to zero or greater.",
      });
    }

    if (maxActiveRuns < 1) {
      issues.push({
        severity: "error",
        title: "Max active runs must be at least 1",
        detail: "Airflow-style concurrency limits require one or more active runs.",
      });
    }

    if (
      backfillStartDate &&
      backfillEndDate &&
      backfillEndDate < backfillStartDate
    ) {
      issues.push({
        severity: "error",
        title: "Backfill window is reversed",
        detail: "End date must be on or after the backfill start date.",
      });
    }

    if (
      (backfillStartDate && !backfillEndDate) ||
      (!backfillStartDate && backfillEndDate)
    ) {
      issues.push({
        severity: "error",
        title: "Backfill window is incomplete",
        detail: "Backfill replay needs both a start and end date.",
      });
    }

    if (clearExistingBackfill && (!backfillStartDate || !backfillEndDate)) {
      issues.push({
        severity: "error",
        title: "Clear-existing replay requires an explicit window",
        detail:
          "Set both start and end dates before clearing existing output for a replay.",
      });
    }

    if (parsedBudget !== null && (!Number.isFinite(parsedBudget) || parsedBudget < 0)) {
      issues.push({
        severity: "error",
        title: "Daily budget is invalid",
        detail: "Daily budget must be a valid non-negative number.",
      });
    }

    if (alertThresholdPercent < 1 || alertThresholdPercent > 100) {
      issues.push({
        severity: "error",
        title: "Alert threshold must stay within 1-100%",
        detail: "Set a threshold between 1 and 100 percent.",
      });
    }

    if (scheduleType === "event" && sensorCount === 0) {
      issues.push({
        severity: "warning",
        title: "No explicit sensor is configured",
        detail:
          "Dataset-triggered runs are configured, but adding a Gate 4 sensor keeps the readiness contract visible in the DAG.",
      });
    }

    if (
      backfillStartDate &&
      backfillEndDate &&
      backfillParallelism > maxActiveRuns
    ) {
      issues.push({
        severity: "warning",
        title: "Replay parallelism exceeds steady-state concurrency",
        detail:
          "Backfill can launch more concurrent work than the normal max active run limit. Confirm your runtime can absorb the spike.",
      });
    }

    if (rollbackTrigger === "manual" && !keepFailedArtifacts) {
      issues.push({
        severity: "warning",
        title: "Manual rollback without failed artifacts reduces recovery context",
        detail:
          "Consider retaining failed artifacts so operators can inspect the broken deployment before rolling back.",
      });
    }

    if (!trackCompute && !trackStorage) {
      issues.push({
        severity: "warning",
        title: "Cost alerts are effectively disabled",
        detail:
          "Enable compute or storage tracking so the budget threshold can surface an operational signal.",
      });
    }

    return issues;
  }, [
    alertThresholdPercent,
    backfillEndDate,
    backfillParallelism,
    backfillStartDate,
    budgetLimitDaily,
    catchupEnabled,
    clearExistingBackfill,
    keepFailedArtifacts,
    maxActiveRuns,
    retryCount,
    rollbackTrigger,
    scheduleType,
    sensorCount,
    trackCompute,
    trackStorage,
    triggerDataset,
  ]);

  const blockingWorkflowIssue = workflowIssues.find(
    (issue) => issue.severity === "error"
  );

  const reload = async () => {
    const data = await api.get<CompositionView>(
      `/api/v1/versions/${versionId}/composition`
    );
    setComposition(data);
  };

  const setGenericPolicyDraftValue = (
    blueprintKey: string,
    paramName: string,
    value: string
  ) => {
    setPolicyDrafts((prev) => ({
      ...prev,
      [blueprintKey]: {
        ...(prev[blueprintKey] ?? {}),
        [paramName]: value,
      },
    }));
  };

  const buildSchedulePolicyConfig = () => {
    const config: Record<string, unknown> = {
      schedule_type: scheduleType,
      timezone: timezone.trim() || "UTC",
      retry_count: retryCount,
    };

    if (scheduleType === "cron") {
      config.cron_expression = scheduleCron.trim() || "@daily";
    }

    if (scheduleType === "event" && triggerDataset.trim()) {
      config.trigger_dataset = triggerDataset.trim();
    }

    return config;
  };

  const buildBackfillPolicyConfig = () => {
    const config: Record<string, unknown> = {
      parallelism: backfillParallelism,
      clear_existing: clearExistingBackfill,
    };

    if (backfillStartDate) {
      config.start_date = backfillStartDate;
    }
    if (backfillEndDate) {
      config.end_date = backfillEndDate;
    }

    return config;
  };

  const buildRollbackPolicyConfig = () => ({
    rollback_trigger: rollbackTrigger,
    keep_failed_artifacts: keepFailedArtifacts,
  });

  const buildCostPolicyConfig = () => {
    const config: Record<string, unknown> = {
      alert_threshold_percent: alertThresholdPercent,
      track_compute: trackCompute,
      track_storage: trackStorage,
    };

    if (budgetLimitDaily.trim()) {
      config.budget_limit_daily = Number.parseFloat(budgetLimitDaily);
    }

    return config;
  };

  const buildGenericPolicyConfig = (blueprint: Blueprint) =>
    Object.fromEntries(
      blueprint.paramsSchema
        .filter((definition) => {
          const rawValue = policyDrafts[blueprint.blueprintKey]?.[definition.name] ?? "";
          return rawValue.trim() !== "";
        })
        .map((definition) => {
          const rawValue =
            policyDrafts[blueprint.blueprintKey]?.[definition.name] ?? "";
          return [definition.name, parsePolicyValue(definition, rawValue)];
        })
    );

  const handleSavePolicy = async () => {
    if (blockingWorkflowIssue) {
      toast.error(blockingWorkflowIssue.detail);
      return;
    }

    setSavingPolicy(true);
    try {
      const policyConfigs: Record<string, object> = {
        [SCHEDULE_POLICY_BLUEPRINT_KEY]: buildSchedulePolicyConfig(),
        [BACKFILL_POLICY_BLUEPRINT_KEY]: buildBackfillPolicyConfig(),
        [ROLLBACK_POLICY_BLUEPRINT_KEY]: buildRollbackPolicyConfig(),
        [COST_POLICY_BLUEPRINT_KEY]: buildCostPolicyConfig(),
      };

      for (const blueprint of genericPolicies) {
        policyConfigs[blueprint.blueprintKey] = buildGenericPolicyConfig(blueprint);
      }

      const updatedVersion = await api.put<PipelineVersion>(
        `/api/v1/tenants/${tenantId}/pipelines/${pipelineId}/versions/${versionId}/orchestration`,
        {
          scheduleCron: scheduleType === "cron" ? scheduleCron.trim() || "@daily" : null,
          catchupEnabled,
          maxActiveRuns,
          dependsOnPast,
          policyConfigs,
        }
      );

      setSavedVersionOverride(updatedVersion);
      toast.success("Orchestration policy saved");
    } catch (error) {
      toast.error(
        error instanceof Error ? error.message : "Failed to save orchestration policy"
      );
    } finally {
      setSavingPolicy(false);
    }
  };

  return (
    <Card className="col-span-3">
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium">Orchestration</CardTitle>
        <CardDescription>
          View and configure schedules, sensors, backfill behavior, rollback,
          and runtime cost guardrails separately from dataflow steps.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {orchestrationInstances.length === 0 ? (
          <div className="rounded-md border border-dashed px-4 py-5 text-sm text-muted-foreground">
            No orchestration steps yet. Add Gate 4 sensor blueprints from the
            pipeline composition step picker to model readiness explicitly.
          </div>
        ) : (
          <div className="space-y-3">
            {orchestrationInstances.map((instance) => {
              const blueprint = instance.blueprintKey
                ? blueprintLookup[instance.blueprintKey]
                : undefined;
              const isGateFourSensor = Boolean(
                instance.blueprintKey &&
                  GATE_FOUR_SENSOR_BLUEPRINT_KEYS.has(instance.blueprintKey)
              );
              return (
                <div
                  key={instance.id}
                  className="rounded-md border px-4 py-3 text-sm"
                >
                  <div className="flex items-start justify-between gap-3">
                    <div className="space-y-1">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="font-medium">{instance.name}</span>
                        {blueprint && (
                          <Badge variant="secondary" className="text-[10px]">
                            {blueprint.name}
                          </Badge>
                        )}
                        {isGateFourSensor && (
                          <Badge variant="outline" className="text-[10px]">
                            Gate 4 sensor
                          </Badge>
                        )}
                      </div>
                      <p className="text-xs text-muted-foreground">
                        {blueprint?.description || "Orchestration step"}
                      </p>
                    </div>
                    {editable && blueprint && (
                      <Button
                        size="sm"
                        variant="outline"
                        className="h-7 text-xs"
                        onClick={() => setSelectedInstance(instance)}
                      >
                        Configure
                      </Button>
                    )}
                  </div>

                  {Object.keys(instance.params ?? {}).length > 0 && (
                    <div className="mt-3 grid gap-2 md:grid-cols-2">
                      {Object.entries(instance.params).map(([key, value]) => (
                        <div
                          key={key}
                          className="rounded bg-muted/40 px-2.5 py-2 text-xs"
                        >
                          <div className="font-medium text-foreground/80">
                            {key}
                          </div>
                          <div className="mt-1 text-muted-foreground break-words">
                            {typeof value === "string"
                              ? value
                              : JSON.stringify(value)}
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}

        <div className="mt-6 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          <div className="rounded-md border px-4 py-3 text-sm">
            <div className="text-xs uppercase text-muted-foreground">Run cadence</div>
            <div className="mt-1 font-medium">{scheduleSummary}</div>
            <div className="mt-1 text-xs text-muted-foreground">
              Timezone {timezone} · retries {retryCount}
            </div>
          </div>
          <div className="rounded-md border px-4 py-3 text-sm">
            <div className="text-xs uppercase text-muted-foreground">Readiness triggers</div>
            <div className="mt-1 font-medium">
              {sensorCount > 0
                ? `${sensorCount} sensor${sensorCount === 1 ? "" : "s"}`
                : scheduleType === "event"
                  ? "Dataset event"
                  : "No explicit sensors"}
            </div>
            <div className="mt-1 text-xs text-muted-foreground">
              {scheduleType === "event" && triggerDataset.trim()
                ? `Watching ${triggerDataset.trim()}`
                : "Gate 4 orchestration stays visible as control-plane config."}
            </div>
          </div>
          <div className="rounded-md border px-4 py-3 text-sm">
            <div className="text-xs uppercase text-muted-foreground">Backfill & replay</div>
            <div className="mt-1 font-medium">{backfillSummary}</div>
            <div className="mt-1 text-xs text-muted-foreground">
              Parallelism {backfillParallelism} · {catchupEnabled ? "Catchup on" : "Catchup off"}
            </div>
          </div>
          <div className="rounded-md border px-4 py-3 text-sm">
            <div className="text-xs uppercase text-muted-foreground">Guardrails</div>
            <div className="mt-1 font-medium">{rollbackSummary}</div>
            <div className="mt-1 text-xs text-muted-foreground">
              {costSummary}
            </div>
          </div>
        </div>

        <div className="mt-6 space-y-4">
          {workflowIssues.length > 0 && (
            <div className="rounded-md border px-4 py-4 text-sm">
              <div className="flex items-center gap-2 flex-wrap">
                <div className="font-medium">Workflow validation</div>
                <Badge variant="outline" className="text-[10px]">
                  {workflowIssues.filter((issue) => issue.severity === "error").length} error
                  {workflowIssues.filter((issue) => issue.severity === "error").length === 1
                    ? ""
                    : "s"}
                  {" · "}
                  {workflowIssues.filter((issue) => issue.severity === "warning").length} warning
                  {workflowIssues.filter((issue) => issue.severity === "warning").length === 1
                    ? ""
                    : "s"}
                </Badge>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">
                Catch invalid orchestration combinations before this version gets
                packaged and deployed.
              </p>
              <div className="mt-3 space-y-2">
                {workflowIssues.map((issue) => (
                  <div
                    key={`${issue.severity}-${issue.title}`}
                    className={`rounded-md border px-3 py-2 ${
                      issue.severity === "error"
                        ? "border-destructive/30 bg-destructive/5"
                        : "border-amber-500/30 bg-amber-500/5"
                    }`}
                  >
                    <div className="flex items-center gap-2">
                      <Badge
                        variant={issue.severity === "error" ? "destructive" : "outline"}
                        className="text-[10px]"
                      >
                        {issue.severity}
                      </Badge>
                      <span className="font-medium">{issue.title}</span>
                    </div>
                    <p className="mt-1 text-xs text-muted-foreground">{issue.detail}</p>
                  </div>
                ))}
              </div>
            </div>
          )}

          <div className="grid gap-4 xl:grid-cols-2">
            <div className="rounded-md border px-4 py-4 text-sm">
              <div className="flex items-center gap-2 flex-wrap">
                <div className="font-medium">Trigger workflow</div>
                <Badge variant="outline" className="text-[10px]">
                  {humanizeEnumValue(scheduleType)}
                </Badge>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">
                {scheduleType === "event"
                  ? "Wait for a dataset signal, then hand execution to the runtime only when readiness checks pass."
                  : scheduleType === "manual"
                    ? "Operators trigger the run explicitly; no automatic cadence or upstream dataset hook will fire."
                    : "Run on a fixed cadence and optionally layer sensors on top for stricter readiness contracts."}
              </p>
              <div className="mt-3 space-y-2 text-xs text-muted-foreground">
                <div>
                  <span className="font-medium text-foreground">Entry point:</span>{" "}
                  {scheduleSummary}
                </div>
                <div>
                  <span className="font-medium text-foreground">Dependency visibility:</span>{" "}
                  {sensorCount > 0
                    ? `${sensorCount} explicit Gate 4 sensor${sensorCount === 1 ? "" : "s"}`
                    : "No explicit readiness sensor"}
                </div>
                <div>
                  <span className="font-medium text-foreground">Runtime behavior:</span>{" "}
                  Timezone {timezone} with {retryCount} retr
                  {retryCount === 1 ? "y" : "ies"}
                </div>
              </div>
            </div>

            <div className="rounded-md border px-4 py-4 text-sm">
              <div className="flex items-center gap-2 flex-wrap">
                <div className="font-medium">Backfill workflow</div>
                <Badge variant="outline" className="text-[10px]">
                  {backfillStartDate && backfillEndDate ? "Explicit window" : "On demand"}
                </Badge>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">
                Preview the replay scope so operators know which medallion layers
                and datasets will be touched before they clear or recompute
                historical output.
              </p>
              <div className="mt-3 space-y-2 text-xs text-muted-foreground">
                <div>
                  <span className="font-medium text-foreground">Replay window:</span>{" "}
                  {backfillSummary}
                </div>
                <div>
                  <span className="font-medium text-foreground">Affected layers:</span>{" "}
                  {orchestrationScope.layers.length > 0
                    ? orchestrationScope.layers.map(formatLayerName).join(" → ")
                    : "No medallion boundary detected yet"}
                </div>
                <div>
                  <span className="font-medium text-foreground">Affected assets:</span>{" "}
                  {orchestrationScope.assetRefs.length > 0
                    ? `${orchestrationScope.assetRefs.length} dataset reference${
                        orchestrationScope.assetRefs.length === 1 ? "" : "s"
                      }`
                    : "No dataflow assets connected yet"}
                </div>
                {orchestrationScope.assetRefs.length > 0 && (
                  <div className="rounded bg-muted/40 px-2.5 py-2 text-[11px]">
                    {orchestrationScope.assetRefs.slice(0, 3).join(" • ")}
                    {orchestrationScope.assetRefs.length > 3 &&
                      ` • +${orchestrationScope.assetRefs.length - 3} more`}
                  </div>
                )}
              </div>
            </div>

            <div className="rounded-md border px-4 py-4 text-sm">
              <div className="flex items-center gap-2 flex-wrap">
                <div className="font-medium">Rollback & recovery workflow</div>
                <Badge variant="outline" className="text-[10px]">
                  {rollbackSummary}
                </Badge>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">
                Make recovery semantics explicit so operators know whether the
                runtime auto-reverts or waits for a manual rollback decision.
              </p>
              <div className="mt-3 space-y-2 text-xs text-muted-foreground">
                <div>
                  <span className="font-medium text-foreground">Rollback trigger:</span>{" "}
                  {rollbackSummary}
                </div>
                <div>
                  <span className="font-medium text-foreground">Failed artifacts:</span>{" "}
                  {keepFailedArtifacts
                    ? "Preserved for diagnosis before recovery."
                    : "Discarded once recovery is complete."}
                </div>
                <div>
                  <span className="font-medium text-foreground">Operator expectation:</span>{" "}
                  {rollbackTrigger === "manual"
                    ? "A human must decide when to roll back."
                    : "The control plane can revert automatically when the trigger fires."}
                </div>
              </div>
            </div>

            <div className="rounded-md border px-4 py-4 text-sm">
              <div className="flex items-center gap-2 flex-wrap">
                <div className="font-medium">Cost guardrail workflow</div>
                <Badge variant="outline" className="text-[10px]">
                  {costSummary}
                </Badge>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">
                Turn the generic cost policy parameters into an operator-facing
                budget posture before a noisy replay or scale-up reaches the runtime.
              </p>
              <div className="mt-3 space-y-2 text-xs text-muted-foreground">
                <div>
                  <span className="font-medium text-foreground">Budget posture:</span>{" "}
                  {budgetLimitDaily.trim()
                    ? `${formatCurrency(Number.parseFloat(budgetLimitDaily))} daily budget with an ${alertThresholdPercent}% alert threshold.`
                    : `No hard budget set; alert once spend reaches ${alertThresholdPercent}% of the default monitoring threshold.`}
                </div>
                <div>
                  <span className="font-medium text-foreground">Signals:</span>{" "}
                  {[
                    trackCompute ? "compute" : null,
                    trackStorage ? "storage" : null,
                  ]
                    .filter(Boolean)
                    .join(" + ") || "none"}
                </div>
              </div>
            </div>
          </div>

          <div className="rounded-md border px-4 py-4 text-sm">
            <div className="flex items-center gap-2 flex-wrap">
              <div className="font-medium">Schedule, triggers, and runtime policy</div>
              <Badge variant="outline" className="text-[10px]">
                pipeline policy
              </Badge>
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              Set how the version runs, whether it waits for upstream events, and
              the core Airflow-style runtime guardrails used during deployment.
            </p>
            <div className="mt-4 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
              <div className="space-y-1">
                <Label className="text-[11px]">Schedule mode</Label>
                {editable ? (
                  <Select
                    value={scheduleType}
                    onValueChange={(value) => setScheduleType(value as ScheduleMode)}
                  >
                    <SelectTrigger className="h-8 text-xs">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="cron">Cron / preset</SelectItem>
                      <SelectItem value="event">Dataset event</SelectItem>
                      <SelectItem value="manual">Manual only</SelectItem>
                    </SelectContent>
                  </Select>
                ) : (
                  <div className="font-medium">{humanizeEnumValue(scheduleType)}</div>
                )}
              </div>

              <div className="space-y-1">
                <Label className="text-[11px]">
                  {scheduleType === "event" ? "Trigger dataset" : "Schedule"}
                </Label>
                {editable ? (
                  scheduleType === "event" ? (
                    <Input
                      value={triggerDataset}
                      onChange={(event) => setTriggerDataset(event.target.value)}
                      placeholder="orders.ready"
                      className="h-8 text-xs"
                    />
                  ) : scheduleType === "manual" ? (
                    <div className="rounded-md border border-dashed px-3 py-2 text-xs text-muted-foreground">
                      Manual runs only — no cron or dataset trigger required.
                    </div>
                  ) : (
                    // W-8: cron-builder is a standalone construct HOSTED here.
                    <CronBuilder
                      value={scheduleCron}
                      onChange={(v) => setScheduleCron(v)}
                    />
                  )
                ) : (
                  <div className="font-medium">{scheduleSummary}</div>
                )}
              </div>

              <div className="space-y-1">
                <Label className="text-[11px]">Timezone</Label>
                {editable ? (
                  <Input
                    value={timezone}
                    onChange={(event) => setTimezone(event.target.value)}
                    className="h-8 text-xs"
                    placeholder="UTC"
                  />
                ) : (
                  <div className="font-medium">{timezone}</div>
                )}
              </div>

              <div className="space-y-1">
                <Label className="text-[11px]">Retry count</Label>
                {editable ? (
                  <Input
                    type="number"
                    min={0}
                    value={String(retryCount)}
                    onChange={(event) =>
                      setRetryCount(Number.parseInt(event.target.value, 10) || 0)
                    }
                    className="h-8 text-xs"
                  />
                ) : (
                  <div className="font-medium">{retryCount}</div>
                )}
              </div>
            </div>

            <div className="mt-4 grid gap-3 md:grid-cols-3">
              <div>
                <div className="text-xs text-muted-foreground">Catchup</div>
                {editable ? (
                  <div className="mt-1 flex items-center gap-2">
                    <input
                      type="checkbox"
                      checked={catchupEnabled}
                      onChange={(event) => setCatchupEnabled(event.target.checked)}
                    />
                    <Label className="text-xs">Enable historical catchup</Label>
                  </div>
                ) : (
                  <div className="font-medium">{catchupEnabled ? "Enabled" : "Disabled"}</div>
                )}
              </div>
              <div>
                <div className="text-xs text-muted-foreground">Max active runs</div>
                {editable ? (
                  <Input
                    type="number"
                    min={1}
                    value={String(maxActiveRuns)}
                    onChange={(event) =>
                      setMaxActiveRuns(Number.parseInt(event.target.value, 10) || 1)
                    }
                    className="mt-1 h-8 text-xs"
                  />
                ) : (
                  <div className="font-medium">{maxActiveRuns}</div>
                )}
              </div>
              <div>
                <div className="text-xs text-muted-foreground">Depends on past</div>
                {editable ? (
                  <div className="mt-1 flex items-center gap-2">
                    <input
                      type="checkbox"
                      checked={dependsOnPast}
                      onChange={(event) => setDependsOnPast(event.target.checked)}
                    />
                    <Label className="text-xs">Require previous run to succeed</Label>
                  </div>
                ) : (
                  <div className="font-medium">{dependsOnPast ? "Yes" : "No"}</div>
                )}
              </div>
            </div>
          </div>

          <div className="grid gap-4 xl:grid-cols-2">
            <div className="rounded-md border px-4 py-4 text-sm">
              <div className="flex items-center gap-2 flex-wrap">
                <div className="font-medium">Backfill & replay</div>
                <Badge variant="outline" className="text-[10px]">
                  replay policy
                </Badge>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">
                Capture the historical replay window and replay strategy instead of
                relying on catchup alone.
              </p>
              <div className="mt-4 grid gap-3 md:grid-cols-2">
                <div className="space-y-1">
                  <Label className="text-[11px]">Start date</Label>
                  {editable ? (
                    <Input
                      type="date"
                      value={backfillStartDate}
                      onChange={(event) => setBackfillStartDate(event.target.value)}
                      className="h-8 text-xs"
                    />
                  ) : (
                    <div className="font-medium">{backfillStartDate || "Not set"}</div>
                  )}
                </div>
                <div className="space-y-1">
                  <Label className="text-[11px]">End date</Label>
                  {editable ? (
                    <Input
                      type="date"
                      value={backfillEndDate}
                      onChange={(event) => setBackfillEndDate(event.target.value)}
                      className="h-8 text-xs"
                    />
                  ) : (
                    <div className="font-medium">{backfillEndDate || "Not set"}</div>
                  )}
                </div>
                <div className="space-y-1">
                  <Label className="text-[11px]">Replay parallelism</Label>
                  {editable ? (
                    <Input
                      type="number"
                      min={1}
                      value={String(backfillParallelism)}
                      onChange={(event) =>
                        setBackfillParallelism(Number.parseInt(event.target.value, 10) || 1)
                      }
                      className="h-8 text-xs"
                    />
                  ) : (
                    <div className="font-medium">{backfillParallelism}</div>
                  )}
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">Clear existing output</div>
                  {editable ? (
                    <div className="mt-1 flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={clearExistingBackfill}
                        onChange={(event) => setClearExistingBackfill(event.target.checked)}
                      />
                      <Label className="text-xs">Clear target partitions before replay</Label>
                    </div>
                  ) : (
                    <div className="font-medium">{clearExistingBackfill ? "Yes" : "No"}</div>
                  )}
                </div>
              </div>
            </div>

            <div className="rounded-md border px-4 py-4 text-sm">
              <div className="flex items-center gap-2 flex-wrap">
                <div className="font-medium">Rollback & cost policy</div>
                <Badge variant="outline" className="text-[10px]">
                  protection policy
                </Badge>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">
                Decide when failed deploys roll back automatically and when cost
                guardrails should alert the team.
              </p>
              <div className="mt-4 grid gap-3 md:grid-cols-2">
                <div className="space-y-1">
                  <Label className="text-[11px]">Rollback trigger</Label>
                  {editable ? (
                    <Select
                      value={rollbackTrigger}
                      onValueChange={(value) =>
                        setRollbackTrigger(value as RollbackTrigger)
                      }
                    >
                      <SelectTrigger className="h-8 text-xs">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="deploy_failure">Deploy failure</SelectItem>
                        <SelectItem value="health_check_failure">Health check failure</SelectItem>
                        <SelectItem value="manual">Manual</SelectItem>
                      </SelectContent>
                    </Select>
                  ) : (
                    <div className="font-medium">{rollbackSummary}</div>
                  )}
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">Failed artifacts</div>
                  {editable ? (
                    <div className="mt-1 flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={keepFailedArtifacts}
                        onChange={(event) => setKeepFailedArtifacts(event.target.checked)}
                      />
                      <Label className="text-xs">Retain failed artifacts for inspection</Label>
                    </div>
                  ) : (
                    <div className="font-medium">{keepFailedArtifacts ? "Keep" : "Discard"}</div>
                  )}
                </div>
                <div className="space-y-1">
                  <Label className="text-[11px]">Daily budget limit</Label>
                  {editable ? (
                    <Input
                      type="number"
                      min={0}
                      step="0.01"
                      value={budgetLimitDaily}
                      onChange={(event) => setBudgetLimitDaily(event.target.value)}
                      className="h-8 text-xs"
                      placeholder="Optional"
                    />
                  ) : (
                    <div className="font-medium">
                      {budgetLimitDaily.trim()
                        ? formatCurrency(Number.parseFloat(budgetLimitDaily))
                        : "Not set"}
                    </div>
                  )}
                </div>
                <div className="space-y-1">
                  <Label className="text-[11px]">Alert threshold %</Label>
                  {editable ? (
                    <Input
                      type="number"
                      min={1}
                      max={100}
                      value={String(alertThresholdPercent)}
                      onChange={(event) =>
                        setAlertThresholdPercent(
                          Number.parseInt(event.target.value, 10) || 80
                        )
                      }
                      className="h-8 text-xs"
                    />
                  ) : (
                    <div className="font-medium">{alertThresholdPercent}%</div>
                  )}
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">Track compute</div>
                  {editable ? (
                    <div className="mt-1 flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={trackCompute}
                        onChange={(event) => setTrackCompute(event.target.checked)}
                      />
                      <Label className="text-xs">Alert on compute spikes</Label>
                    </div>
                  ) : (
                    <div className="font-medium">{trackCompute ? "Enabled" : "Disabled"}</div>
                  )}
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">Track storage</div>
                  {editable ? (
                    <div className="mt-1 flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={trackStorage}
                        onChange={(event) => setTrackStorage(event.target.checked)}
                      />
                      <Label className="text-xs">Alert on storage growth</Label>
                    </div>
                  ) : (
                    <div className="font-medium">{trackStorage ? "Enabled" : "Disabled"}</div>
                  )}
                </div>
              </div>
            </div>
          </div>

          {genericPolicies.length > 0 && (
            <div>
              <div className="text-xs font-medium uppercase text-muted-foreground">
                Additional policy blueprint catalog
              </div>
              <div className="mt-2 grid gap-2 md:grid-cols-2">
                {genericPolicies.map((blueprint) => (
                  <div key={blueprint.id} className="rounded-md border px-4 py-3 text-sm">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="font-medium">{blueprint.name}</span>
                      <Badge variant="outline" className="text-[10px]">
                        pipeline policy
                      </Badge>
                    </div>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {blueprint.description}
                    </p>
                    {blueprint.paramsSchema.length > 0 && editable && (
                      <div className="mt-3 grid gap-2">
                        {blueprint.paramsSchema.map((param) => {
                          const value =
                            policyDrafts[blueprint.blueprintKey]?.[param.name] ?? "";
                          return (
                            <div key={`${blueprint.id}-${param.name}`} className="space-y-1">
                              <Label className="text-[11px]">
                                {humanizeKey(param.name)}
                              </Label>
                              {param.type === "boolean" ? (
                                <Select
                                  value={value || "false"}
                                  onValueChange={(nextValue) =>
                                    setGenericPolicyDraftValue(
                                      blueprint.blueprintKey,
                                      param.name,
                                      nextValue
                                    )
                                  }
                                >
                                  <SelectTrigger className="h-8 text-xs">
                                    <SelectValue />
                                  </SelectTrigger>
                                  <SelectContent>
                                    <SelectItem value="true">true</SelectItem>
                                    <SelectItem value="false">false</SelectItem>
                                  </SelectContent>
                                </Select>
                              ) : param.options && param.options.length > 0 ? (
                                <Select
                                  value={value}
                                  onValueChange={(nextValue) =>
                                    setGenericPolicyDraftValue(
                                      blueprint.blueprintKey,
                                      param.name,
                                      nextValue
                                    )
                                  }
                                >
                                  <SelectTrigger className="h-8 text-xs">
                                    <SelectValue placeholder={`Select ${param.name}`} />
                                  </SelectTrigger>
                                  <SelectContent>
                                    {param.options.map((option) => (
                                      <SelectItem key={option} value={option}>
                                        {humanizeEnumValue(option)}
                                      </SelectItem>
                                    ))}
                                  </SelectContent>
                                </Select>
                              ) : (
                                <Input
                                  type={param.type === "date" ? "date" : param.type === "number" || param.type === "integer" ? "number" : "text"}
                                  value={value}
                                  onChange={(event) =>
                                    setGenericPolicyDraftValue(
                                      blueprint.blueprintKey,
                                      param.name,
                                      event.target.value
                                    )
                                  }
                                  className="h-8 text-xs"
                                />
                              )}
                              {param.description && (
                                <p className="text-[11px] text-muted-foreground">
                                  {param.description}
                                </p>
                              )}
                            </div>
                          );
                        })}
                      </div>
                    )}
                    {blueprint.paramsSchema.length > 0 && !editable && (
                      <div className="mt-3 flex flex-wrap gap-1">
                        {blueprint.paramsSchema.map((param) => (
                          <Badge
                            key={`${blueprint.id}-${param.name}`}
                            variant="secondary"
                            className="text-[10px]"
                          >
                            {humanizeKey(param.name)}
                          </Badge>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}

          <div className="rounded-md border bg-muted/20 px-4 py-3 text-xs text-muted-foreground">
            Gate 4 now exposes orchestration as a first-class surface: schedules,
            event triggers, backfill windows, rollback behavior, and cost policy
            all save back to the version orchestration config for this pipeline.
          </div>

          {editable && (
            <div className="flex justify-end">
              <Button
                size="sm"
                className="h-8 text-xs"
                onClick={handleSavePolicy}
                disabled={savingPolicy || Boolean(blockingWorkflowIssue)}
              >
                {savingPolicy ? "Saving..." : "Save Orchestration Policy"}
              </Button>
            </div>
          )}
        </div>
      </CardContent>

      {selectedInstance && (
        <ConfigureTransformDialog
          open
          onOpenChange={(open) => {
            if (!open) {
              setSelectedInstance(null);
            }
          }}
          instanceId={selectedInstance.id}
          instanceName={selectedInstance.name}
          blueprintKey={selectedInstance.blueprintKey || ""}
          blueprint={
            selectedInstance.blueprintKey
              ? blueprintLookup[selectedInstance.blueprintKey]
              : undefined
          }
          versionId={versionId}
          currentParams={selectedInstance.params ?? {}}
          onSaved={async () => {
            await reload();
            setSelectedInstance(null);
          }}
        />
      )}
    </Card>
  );
}
