package com.pulse.chat.diff;

import com.pulse.chat.orchestration.StagingGraph;
import com.pulse.chat.orchestration.StagingGraph.StagingInstance;
import com.pulse.chat.orchestration.StagingGraph.StagingWire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The canonical-vs-staging DIFF — PULSE's analogue of n8n's
 * {@code compareWorkflowsNodes} (06-ops-queue-apply-diff.md §G), re-expressed for
 * the PULSE graph and computed PRE-commit (the inversion: the diff IS the Plan
 * Preview, not a post-hoc audit).
 *
 * <p>Instance diff: keyed by {@code instanceRef} (= name). Four statuses
 * ({@code equal}/{@code modified}/{@code added}/{@code deleted}). Equality is
 * content-based over a FIXED projection — the PULSE analogue of n8n's
 * {@code ['name','type','typeVersion','parameters']}: {@code {name, blueprintKey,
 * blueprintVersion, params, lakeLayer, lakeFormat, storageBackend}}. Secrets are
 * excluded (SecretRefs, ADR 0023); {@code dqExpectations}/{@code executionOrder}
 * are excluded to avoid noisy diffs (decision 3-12).</p>
 *
 * <p>Wiring diff is a parallel set-diff over {@code {sourceRef,sourcePort,
 * targetRef,targetPort}} (the PULSE analogue of n8n's separate
 * {@code compareConnections}).</p>
 */
public final class CompareGraphs {

    private CompareGraphs() {}

    public enum Status { EQUAL, MODIFIED, ADDED, DELETED }

    public record InstanceDiff(String instanceRef, Status status, StagingInstance instance) {}

    public record WireDiff(Status status, StagingWire wire) {}

    /** The full diff result: instance diffs keyed by ref + wiring diffs. */
    public record GraphDiff(
            Map<String, InstanceDiff> instances,
            List<WireDiff> wirings) {

        /** "Review N changes" count = non-equal instances + changed wires (06 §G). */
        public int changeCount() {
            long instChanges = instances.values().stream()
                    .filter(d -> d.status() != Status.EQUAL)
                    .count();
            long wireChanges = wirings.stream()
                    .filter(d -> d.status() != Status.EQUAL)
                    .count();
            return (int) (instChanges + wireChanges);
        }
    }

    /**
     * Compute {@code compareGraphs(canonical, staging)} with the four-status
     * logic (06 §G): a ref in canonical-not-staging = deleted; in both but
     * content-differs = modified; identical = equal; in staging-not-canonical =
     * added.
     */
    public static GraphDiff compare(StagingGraph canonical, StagingGraph staging) {
        Map<String, StagingInstance> base = byRef(canonical.instances());
        Map<String, StagingInstance> target = byRef(staging.instances());

        Map<String, InstanceDiff> instanceDiffs = new LinkedHashMap<>();
        for (var entry : base.entrySet()) {
            String ref = entry.getKey();
            if (!target.containsKey(ref)) {
                instanceDiffs.put(ref, new InstanceDiff(ref, Status.DELETED, entry.getValue()));
            } else if (!instancesEqual(entry.getValue(), target.get(ref))) {
                instanceDiffs.put(ref, new InstanceDiff(ref, Status.MODIFIED, target.get(ref)));
            } else {
                instanceDiffs.put(ref, new InstanceDiff(ref, Status.EQUAL, target.get(ref)));
            }
        }
        for (var entry : target.entrySet()) {
            if (!base.containsKey(entry.getKey())) {
                instanceDiffs.put(entry.getKey(),
                        new InstanceDiff(entry.getKey(), Status.ADDED, entry.getValue()));
            }
        }

        List<WireDiff> wireDiffs = compareWires(canonical.wirings(), staging.wirings());
        return new GraphDiff(instanceDiffs, wireDiffs);
    }

    /** Content equality over the fixed projection (3-12). */
    static boolean instancesEqual(StagingInstance a, StagingInstance b) {
        return Objects.equals(a.ref(), b.ref())
                && Objects.equals(a.blueprintKey(), b.blueprintKey())
                && Objects.equals(a.blueprintVersion(), b.blueprintVersion())
                && Objects.equals(a.params(), b.params())
                && Objects.equals(a.lakeLayer(), b.lakeLayer())
                && Objects.equals(a.lakeFormat(), b.lakeFormat())
                && Objects.equals(a.storageBackend(), b.storageBackend());
    }

    private static List<WireDiff> compareWires(List<StagingWire> base, List<StagingWire> target) {
        List<WireDiff> out = new ArrayList<>();
        for (StagingWire w : base) {
            boolean inTarget = target.stream().anyMatch(t -> sameWire(t, w));
            out.add(new WireDiff(inTarget ? Status.EQUAL : Status.DELETED, w));
        }
        for (StagingWire w : target) {
            boolean inBase = base.stream().anyMatch(b -> sameWire(b, w));
            if (!inBase) out.add(new WireDiff(Status.ADDED, w));
        }
        return out;
    }

    private static boolean sameWire(StagingWire a, StagingWire b) {
        return Objects.equals(a.sourceRef(), b.sourceRef())
                && Objects.equals(a.sourcePort(), b.sourcePort())
                && Objects.equals(a.targetRef(), b.targetRef())
                && Objects.equals(a.targetPort(), b.targetPort());
    }

    private static Map<String, StagingInstance> byRef(List<StagingInstance> list) {
        Map<String, StagingInstance> m = new LinkedHashMap<>();
        for (StagingInstance i : list) m.put(i.ref(), i);
        return m;
    }
}
