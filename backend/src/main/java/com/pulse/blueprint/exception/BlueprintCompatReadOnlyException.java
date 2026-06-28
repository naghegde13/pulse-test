package com.pulse.blueprint.exception;

/**
 * Thrown when generic composition mutation paths target a deprecated or
 * deferred blueprint instance (ARCH-014). Mapped to HTTP 422 by
 * {@code GlobalExceptionHandler} with stable code {@code BLUEPRINT_COMPAT_READ_ONLY}.
 */
public class BlueprintCompatReadOnlyException extends RuntimeException {

    private final String blueprintKey;
    private final String replacementBlueprintKey;

    public BlueprintCompatReadOnlyException(String blueprintKey, String replacementBlueprintKey) {
        super(buildDetail(blueprintKey, replacementBlueprintKey));
        this.blueprintKey = blueprintKey;
        this.replacementBlueprintKey = replacementBlueprintKey;
    }

    public String getBlueprintKey() {
        return blueprintKey;
    }

    public String getReplacementBlueprintKey() {
        return replacementBlueprintKey;
    }

    private static String buildDetail(String blueprintKey, String replacement) {
        if (replacement != null && !replacement.isBlank()) {
            return "Deprecated blueprint instances are read-only compatibility artifacts; "
                    + "migrate to " + replacement + " before editing.";
        }
        return "Deprecated blueprint instances are read-only compatibility artifacts; "
                + "migration requires manual redesign because no replacement is registered.";
    }
}
