package com.pulse.storage.model;

/**
 * Medallion layer for a lake-resident table. Drives the path-fragment used
 * by PathConventionService.tableLocation() and the legal lake_format
 * matrix in StorageBackendValidator.
 */
public enum LakeLayer {
    BRONZE,
    SILVER,
    GOLD;

    public String pathFragment() { return name().toLowerCase(); }

    public String dbValue() { return pathFragment(); }

    public static LakeLayer from(String raw) {
        if (raw == null) return null;
        return LakeLayer.valueOf(raw.toUpperCase());
    }
}
