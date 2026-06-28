package com.pulse.chat.orchestration;

import java.util.List;
import java.util.Map;

/**
 * The PULSE analogue of n8n's {@code WorkflowOperation} discriminated union
 * (06-ops-queue-apply-diff.md §E). A mutation tool NEVER touches the canonical
 * graph or the Command Log — it emits exactly ONE typed {@code PlanOperation}
 * into the per-turn op-queue ({@link OpQueue}), which {@code process_operations}
 * drains as a single immutable fold onto the STAGING graph ({@link StagingGraph}).
 *
 * <p>Ops are keyed by {@code instanceRef} = the instance NAME (the stable
 * pre-apply handle; staged instances have no id yet — n8n keys connections by
 * node name too, and PULSE resolves refs → real ids at apply time via
 * {@code Plan.draftRefDeclarations}/{@code draftRefBindings}).</p>
 *
 * <p>Each variant carries a {@code reasoning} string (the per-op rationale shown
 * in the Plan Preview). {@code addInstances} carries NO initial params (decision
 * 3-F4: a following {@code set_params}/{@code updateInstance} op carries values),
 * so each op is individually previewable and validated.</p>
 *
 * <p>The {@code op} discriminator strings match the §E mapping table exactly.</p>
 */
public sealed interface PlanOperation
        permits PlanOperation.AddInstances,
                PlanOperation.RemoveInstance,
                PlanOperation.UpdateInstance,
                PlanOperation.SetWiring,
                PlanOperation.MergeWiring,
                PlanOperation.RemoveWiring,
                PlanOperation.Rename,
                PlanOperation.SetName,
                PlanOperation.SetPipelineSetting,
                PlanOperation.Clear {

    /** The discriminator string (matches 06 §E mapping + the {@code composition.*} command map). */
    String op();

    /** Per-op rationale surfaced in the Plan Preview. May be empty for the {@code clear} control op. */
    String reasoning();

    // ------------------------------------------------------------------
    // Variants
    // ------------------------------------------------------------------

    /**
     * Add one or more Blueprint instances. Carries NO initial params (3-F4).
     * Each spec is {@code {ref (name), blueprintKey}} plus optional canonical
     * storage fields; params arrive via a following {@link UpdateInstance}.
     */
    record AddInstances(List<InstanceSpec> instances, String reasoning) implements PlanOperation {
        public String op() { return "addInstances"; }
    }

    /** A single instance to add (by name = its {@code instanceRef}). */
    record InstanceSpec(
            String ref,
            String blueprintKey,
            String storageBackend,
            String lakeLayer,
            String lakeFormat) {}

    /** Remove an instance (by ref) and its incident wirings. */
    record RemoveInstance(String instanceRef, String reasoning) implements PlanOperation {
        public String op() { return "removeInstance"; }
    }

    /** Update an instance's params / lakeLayer / lakeFormat / storageBackend (STRUCTURED, no sub-LLM). */
    record UpdateInstance(
            String instanceRef,
            Map<String, Object> params,
            String storageBackend,
            String lakeLayer,
            String lakeFormat,
            String reasoning) implements PlanOperation {
        public String op() { return "updateInstance"; }
    }

    /** Replace the WHOLE wiring set (Modify-rebuild / pipeline-create). */
    record SetWiring(List<WireSpec> wirings, String reasoning) implements PlanOperation {
        public String op() { return "setWiring"; }
    }

    /** Additive, dedup'd wiring add — the default for {@code wire_ports}. */
    record MergeWiring(List<WireSpec> wirings, String reasoning) implements PlanOperation {
        public String op() { return "mergeWiring"; }
    }

    /** Remove one edge. */
    record RemoveWiring(WireSpec wiring, String reasoning) implements PlanOperation {
        public String op() { return "removeWiring"; }
    }

    /**
     * One wire, keyed by instance REFS (names) end-to-end (the diff + apply key).
     * The dedup key for {@link MergeWiring} is the whole 4-tuple.
     */
    record WireSpec(
            String sourceRef,
            String sourcePort,
            String targetRef,
            String targetPort) {}

    /** Rename a step + fix references to it. */
    record Rename(String oldRef, String newRef, String reasoning) implements PlanOperation {
        public String op() { return "rename"; }
    }

    /** Rename the pipeline (folds to {@code pipeline.update} at Apply, §7.16 #9). */
    record SetName(String name, String reasoning) implements PlanOperation {
        public String op() { return "setName"; }
    }

    /**
     * PULSE-specific — portless orchestration-policy settings (ADR 0020/0021).
     * Folds to {@code pipeline.update} at Apply (§7.16 #9).
     */
    record SetPipelineSetting(Map<String, Object> settings, String reasoning) implements PlanOperation {
        public String op() { return "setPipelineSetting"; }
    }

    /** Reducer control only — empties the op-queue (06 §B.2). */
    record Clear() implements PlanOperation {
        public String op() { return "clear"; }
        public String reasoning() { return ""; }
    }
}
