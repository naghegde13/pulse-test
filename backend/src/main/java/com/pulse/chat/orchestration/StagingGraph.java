package com.pulse.chat.orchestration;

import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.service.CompositionService.CompositionView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The STAGING graph — the candidate composition a turn builds, NEVER persisted
 * until Apply (07-orchestration-revert-layout.md §2.2; 06 §C/§E). It is a plain,
 * freely-cloneable value model keyed by {@code instanceRef} = instance NAME (the
 * stable pre-apply handle, since staged instances have no id yet).
 *
 * <p>{@link #applyOps(StagingGraph, List)} is the immutable fold (n8n's
 * {@code applyOperations}): it starts from a COPY of the base graph and applies
 * each op IN SEQUENCE, all-or-nothing per superstep. {@code process_operations}
 * runs this against {@code clone(canonical)} — the STAGING graph — never the
 * canonical graph.</p>
 */
public final class StagingGraph {

    /** An instance in the staging graph, keyed by {@code ref} (= name). */
    public record StagingInstance(
            String ref,
            String blueprintKey,
            String blueprintVersion,
            Map<String, Object> params,
            String storageBackend,
            String lakeLayer,
            String lakeFormat) {

        StagingInstance withParams(Map<String, Object> mergedParams,
                                   String backend, String layer, String format) {
            return new StagingInstance(
                    ref, blueprintKey, blueprintVersion, mergedParams,
                    backend != null ? backend : storageBackend,
                    layer != null ? layer : lakeLayer,
                    format != null ? format : lakeFormat);
        }

        StagingInstance withRef(String newRef) {
            return new StagingInstance(
                    newRef, blueprintKey, blueprintVersion, params,
                    storageBackend, lakeLayer, lakeFormat);
        }
    }

    /** A wire in the staging graph, keyed by instance REFS end-to-end. */
    public record StagingWire(
            String sourceRef, String sourcePort,
            String targetRef, String targetPort) {}

    private final String name;
    // Insertion-ordered, keyed by ref for O(1) lookup + stable iteration.
    private final Map<String, StagingInstance> instances;
    private final List<StagingWire> wirings;

    public StagingGraph(String name,
                        Map<String, StagingInstance> instances,
                        List<StagingWire> wirings) {
        this.name = name == null ? "" : name;
        this.instances = new LinkedHashMap<>(instances);
        this.wirings = new ArrayList<>(wirings);
    }

    public static StagingGraph empty() {
        return new StagingGraph("", new LinkedHashMap<>(), new ArrayList<>());
    }

    public String name() { return name; }

    public List<StagingInstance> instances() { return List.copyOf(instances.values()); }

    public StagingInstance instance(String ref) { return instances.get(ref); }

    public List<StagingWire> wirings() { return List.copyOf(wirings); }

    /** A deep copy — the per-turn STAGING graph starts as a clone of canonical. */
    public StagingGraph copy() {
        return new StagingGraph(name, new LinkedHashMap<>(instances), new ArrayList<>(wirings));
    }

    /**
     * Project a persisted {@link CompositionView} (canonical) into a STAGING
     * value model. Keyed by instance NAME (the ref), so staged-by-name and
     * canonical instances diff cleanly.
     */
    public static StagingGraph fromCanonical(CompositionView view, String pipelineName) {
        Map<String, StagingInstance> insts = new LinkedHashMap<>();
        for (SubPipelineInstance i : view.instances()) {
            insts.put(i.getName(), new StagingInstance(
                    i.getName(),
                    i.getBlueprintKey(),
                    i.getBlueprintVersion(),
                    i.getParams() == null ? Map.of() : new LinkedHashMap<>(i.getParams()),
                    i.getStorageBackend(),
                    i.getLakeLayer(),
                    i.getLakeFormat()));
        }
        // Build id→name index so canonical wirings (keyed by id) re-key to refs.
        Map<String, String> idToName = new LinkedHashMap<>();
        for (SubPipelineInstance i : view.instances()) {
            idToName.put(i.getId(), i.getName());
        }
        List<StagingWire> wires = new ArrayList<>();
        for (PortWiring w : view.wirings()) {
            String src = idToName.getOrDefault(w.getSourceInstanceId(), w.getSourceInstanceId());
            String tgt = idToName.getOrDefault(w.getTargetInstanceId(), w.getTargetInstanceId());
            wires.add(new StagingWire(src, w.getSourcePortName(), tgt, w.getTargetPortName()));
        }
        return new StagingGraph(pipelineName == null ? "" : pipelineName, insts, wires);
    }

    // ------------------------------------------------------------------
    // The immutable fold (n8n applyOperations) — start from a COPY, never mutate input.
    // ------------------------------------------------------------------

    /**
     * Apply the op-queue as a single fold in op order onto a COPY of {@code base}.
     * All-or-nothing per superstep: a thrown op aborts without partial mutation
     * of {@code base} (the caller's base is never touched).
     */
    public static StagingGraph applyOps(StagingGraph base, List<PlanOperation> operations) {
        StagingGraph result = base.copy();
        for (PlanOperation op : operations) {
            result = apply(result, op);
        }
        return result;
    }

    private static StagingGraph apply(StagingGraph g, PlanOperation op) {
        Map<String, StagingInstance> insts = new LinkedHashMap<>(g.instances);
        List<StagingWire> wires = new ArrayList<>(g.wirings);
        String name = g.name;

        switch (op) {
            case PlanOperation.Clear ignored -> {
                return StagingGraph.empty();
            }
            case PlanOperation.AddInstances add -> {
                for (PlanOperation.InstanceSpec spec : add.instances()) {
                    insts.put(spec.ref(), new StagingInstance(
                            spec.ref(), spec.blueprintKey(), null,
                            new LinkedHashMap<>(),
                            spec.storageBackend(), spec.lakeLayer(), spec.lakeFormat()));
                }
            }
            case PlanOperation.RemoveInstance rm -> {
                insts.remove(rm.instanceRef());
                // Drop incident wirings.
                wires.removeIf(w -> w.sourceRef().equals(rm.instanceRef())
                        || w.targetRef().equals(rm.instanceRef()));
            }
            case PlanOperation.UpdateInstance up -> {
                StagingInstance cur = insts.get(up.instanceRef());
                if (cur != null) {
                    Map<String, Object> merged = new LinkedHashMap<>(cur.params());
                    if (up.params() != null) merged.putAll(up.params());
                    insts.put(up.instanceRef(), cur.withParams(
                            merged, up.storageBackend(), up.lakeLayer(), up.lakeFormat()));
                }
            }
            case PlanOperation.SetWiring set -> {
                wires = new ArrayList<>();
                for (PlanOperation.WireSpec ws : set.wirings()) {
                    wires.add(toWire(ws));
                }
            }
            case PlanOperation.MergeWiring merge -> {
                for (PlanOperation.WireSpec ws : merge.wirings()) {
                    StagingWire candidate = toWire(ws);
                    // Additive dedup on the full 4-tuple {source,sourcePort,target,targetPort}.
                    boolean exists = wires.stream().anyMatch(w -> sameWire(w, candidate));
                    if (!exists) wires.add(candidate);
                }
            }
            case PlanOperation.RemoveWiring rmw -> {
                StagingWire target = toWire(rmw.wiring());
                wires.removeIf(w -> sameWire(w, target));
            }
            case PlanOperation.Rename ren -> {
                StagingInstance cur = insts.remove(ren.oldRef());
                if (cur != null) {
                    // Preserve insertion order is best-effort; LinkedHashMap re-puts at end.
                    insts.put(ren.newRef(), cur.withRef(ren.newRef()));
                }
                // Fix wiring references.
                List<StagingWire> rekeyed = new ArrayList<>();
                for (StagingWire w : wires) {
                    String s = w.sourceRef().equals(ren.oldRef()) ? ren.newRef() : w.sourceRef();
                    String t = w.targetRef().equals(ren.oldRef()) ? ren.newRef() : w.targetRef();
                    rekeyed.add(new StagingWire(s, w.sourcePort(), t, w.targetPort()));
                }
                wires = rekeyed;
            }
            case PlanOperation.SetName sn -> {
                name = sn.name();
            }
            case PlanOperation.SetPipelineSetting ignored -> {
                // Pipeline-level setting — does not change the composition GRAPH
                // shape; carried to Apply and folded into pipeline.update. No
                // graph mutation here.
            }
        }
        return new StagingGraph(name, insts, wires);
    }

    private static StagingWire toWire(PlanOperation.WireSpec ws) {
        return new StagingWire(ws.sourceRef(), ws.sourcePort(), ws.targetRef(), ws.targetPort());
    }

    private static boolean sameWire(StagingWire a, StagingWire b) {
        return Objects.equals(a.sourceRef(), b.sourceRef())
                && Objects.equals(a.sourcePort(), b.sourcePort())
                && Objects.equals(a.targetRef(), b.targetRef())
                && Objects.equals(a.targetPort(), b.targetPort());
    }
}
