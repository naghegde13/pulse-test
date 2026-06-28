package com.pulse.blueprint.exception;

/**
 * Thrown when a generic composition path attempts to instantiate a blueprint
 * that is not on the {@code composition} add_surface (ARCH-011). Mapped to
 * HTTP 422 with stable code {@code BLUEPRINT_NOT_ADDABLE}.
 */
public class BlueprintNotAddableException extends RuntimeException {

    private final String blueprintKey;
    private final String addSurface;

    public BlueprintNotAddableException(String blueprintKey, String addSurface, String detail) {
        super(detail);
        this.blueprintKey = blueprintKey;
        this.addSurface = addSurface;
    }

    public String getBlueprintKey() {
        return blueprintKey;
    }

    public String getAddSurface() {
        return addSurface;
    }
}
