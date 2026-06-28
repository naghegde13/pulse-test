package com.pulse.storage.model;

/**
 * File-flow lifecycle folders inside the files bucket.
 *
 * <p>Distinct from medallion table layers (those live in the lake bucket).
 * SRC/Processing/Archive cover the inbound-file dance; bad_files holds
 * corrupt/malformed files quarantined at ingest time;
 * outgoing_extracts holds files PULSE produces for downstream consumers
 * (reconciliation reports, exports).
 *
 * <p>The DB folder name is the conventional case used by PULSE
 * (kebab-case for multi-word, capitalized for single words preserved
 * from the user's original spec).
 */
public enum FileLifecycle {
    SRC("SRC"),
    PROCESSING("Processing"),
    ARCHIVE("Archive"),
    BAD_FILES("bad_files"),
    OUTGOING_EXTRACTS("outgoing_extracts");

    private final String folder;

    FileLifecycle(String folder) { this.folder = folder; }

    public String folder() { return folder; }
}
