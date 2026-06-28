package com.pulse.pipeline.opengine;

import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Classifies a surfaced downstream schema conflict into the 3-tier model
 * (SPEC #1 §B.2): <b>breaking</b> / <b>partial</b> / <b>non-breaking</b>, and
 * computes an impact radius. The tier drives the impact-radius overlay the UI
 * renders (the overlay <em>rendering</em> is #3's concern; the <em>classification</em>
 * is this one's).
 *
 * <ul>
 *   <li><b>breaking</b> — a referenced column is gone, or wrong-typed in a way
 *       that invalidates a downstream op (e.g. a join key dropped, a type change
 *       that a downstream op cannot consume). Maps from {@code MISSING_COLUMN}
 *       on a required reference, and from an incompatible {@code TYPE_MISMATCH}.</li>
 *   <li><b>partial</b> — a downstream op still resolves but loses coverage, e.g.
 *       a column it referenced by an old name was renamed (the op runs on what
 *       remains, but the intended column is no longer wired). Maps from a
 *       compatible/widening {@code TYPE_MISMATCH} and from a rename-shadowed
 *       reference.</li>
 *   <li><b>non-breaking</b> — additive or cosmetic (a new column appeared; a
 *       column gained a tag). No downstream op is invalidated.</li>
 * </ul>
 */
@Service
public class ConflictClassifier {

    public enum Tier { BREAKING, PARTIAL, NON_BREAKING }

    /** Legacy conflict-type strings the existing emitConflict path uses. */
    public static final String MISSING_COLUMN = "MISSING_COLUMN";
    public static final String TYPE_MISMATCH = "TYPE_MISMATCH";
    public static final String ADDED_COLUMN = "ADDED_COLUMN";
    public static final String RENAMED_COLUMN = "RENAMED_COLUMN";

    /** Numeric/temporal widenings that are non-invalidating (partial, not breaking). */
    private static final Set<String> WIDENINGS = Set.of(
            "integer->long", "integer->double", "integer->decimal",
            "long->double", "long->decimal",
            "float->double", "decimal->double", "date->timestamp");

    /**
     * The classification result: the tier, the conflict type, and the set of
     * downstream instances/ports impacted (the impact radius).
     */
    public record Classification(Tier tier, String conflictType, Set<String> impactRadius) {}

    /**
     * Classify a conflict.
     *
     * @param conflictType   one of the conflict-type strings above
     * @param fromType       the upstream-produced type (for TYPE_MISMATCH; may be null)
     * @param toType         the type the downstream op expects (for TYPE_MISMATCH; may be null)
     * @param requiredByOp   true if the affected column is required by a downstream op
     *                       (a join key, a group-by column, a referenced expr column)
     * @param downstreamRefs the downstream instance/port references that consume the
     *                       affected column (the impact radius seed)
     */
    public Classification classify(String conflictType,
                                   String fromType,
                                   String toType,
                                   boolean requiredByOp,
                                   List<String> downstreamRefs) {
        Set<String> radius = new LinkedHashSet<>();
        if (downstreamRefs != null) radius.addAll(downstreamRefs);

        Tier tier = switch (conflictType == null ? "" : conflictType) {
            case MISSING_COLUMN ->
                    // A missing column required by a downstream op invalidates it (breaking);
                    // a missing column nobody downstream requires is partial (lost coverage).
                    requiredByOp ? Tier.BREAKING : Tier.PARTIAL;
            case TYPE_MISMATCH ->
                    classifyTypeMismatch(fromType, toType, requiredByOp);
            case RENAMED_COLUMN ->
                    // A rename shadows the old-name reference: the op still resolves on what
                    // remains but loses the intended column => partial.
                    Tier.PARTIAL;
            case ADDED_COLUMN ->
                    Tier.NON_BREAKING;
            default ->
                    // Unknown conflict-type: be conservative — surface as breaking so it is
                    // never silently swallowed.
                    Tier.BREAKING;
        };
        return new Classification(tier, conflictType, radius);
    }

    private Tier classifyTypeMismatch(String fromType, String toType, boolean requiredByOp) {
        if (fromType == null || toType == null) {
            return requiredByOp ? Tier.BREAKING : Tier.PARTIAL;
        }
        if (fromType.equalsIgnoreCase(toType)) {
            return Tier.NON_BREAKING;
        }
        String key = norm(fromType) + "->" + norm(toType);
        if (WIDENINGS.contains(key)) {
            // A widening keeps the downstream op resolvable but changes coverage => partial.
            return Tier.PARTIAL;
        }
        // A non-widening, incompatible type change to a required column invalidates the op.
        return requiredByOp ? Tier.BREAKING : Tier.PARTIAL;
    }

    private String norm(String t) {
        String s = t == null ? "" : t.toLowerCase();
        return switch (s) {
            case "int" -> "integer";
            case "bigint" -> "long";
            case "numeric" -> "decimal";
            default -> s;
        };
    }
}
