package com.pulse.blueprint.model;

/**
 * Add surface (ARCH-011). Drives which generic mutation paths may instantiate
 * or configure a blueprint:
 *
 * <ul>
 *   <li>{@link #COMPOSITION} - addable as a {@code SubPipelineInstance} node.</li>
 *   <li>{@link #ORCHESTRATION_POLICY} - editable only through
 *       {@code PipelineService.updateOrchestration}.</li>
 *   <li>{@link #NONE} - never addable through generic mutation paths
 *       (deprecated, deferred, or internal-only rows).</li>
 * </ul>
 *
 * <p>{@link #ALL} is a query-only sentinel used by admin/debug list paths; it
 * is never persisted on a {@link Blueprint} row.</p>
 */
public enum BlueprintSurface {
    COMPOSITION("composition"),
    ORCHESTRATION_POLICY("orchestration_policy"),
    NONE("none"),
    ALL("all");

    private final String wire;

    BlueprintSurface(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static BlueprintSurface fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (BlueprintSurface s : values()) {
            if (s.wire.equalsIgnoreCase(value) || s.name().equalsIgnoreCase(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException(
                "Invalid blueprint surface '" + value
                        + "'. Valid values: composition, orchestration_policy, none, all");
    }
}
