package com.pulse.pipeline.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ARCH-007 — Typed model for dataset-event scheduling references.
 *
 * <p>An {@code EventRef} represents a single upstream event that a
 * pipeline version depends on when its schedule type is
 * {@code dataset_event}.  Two event types are supported:</p>
 * <ul>
 *   <li><b>table_ready</b> — fires when an upstream table contract is
 *       satisfied.  Identified by {@code tableContractId} (resolved) or
 *       {@code tableRef} (preview/draft mode).</li>
 *   <li><b>pipeline_event</b> — fires when an upstream pipeline emits a
 *       named event (defaults to {@code pipeline_complete}).</li>
 * </ul>
 *
 * <p>This is a plain record (not a JPA entity). It is serialised into
 * the version metadata JSONB column under
 * {@code orchestrationPolicy.event_refs}.</p>
 */
public record EventRef(
        String eventType,
        String tableContractId,
        TableRefPreview tableRef,
        String pipelineId,
        String eventSlug,
        String description
) {

    // ------------------------------------------------------------------ inner record

    /**
     * Lightweight table reference used in preview/draft mode before a
     * formal table contract ID has been assigned.
     */
    public record TableRefPreview(
            String domainSlug,
            String layer,
            String tableSlug
    ) {}

    // ------------------------------------------------------------------ compact constructors

    public EventRef {
        if (eventSlug == null || eventSlug.isBlank()) {
            eventSlug = "pipeline_complete";
        }
    }

    // ------------------------------------------------------------------ URI generation

    /**
     * Produce the canonical PULSE dataset URI for this event reference.
     *
     * <ul>
     *   <li>{@code table_ready} →
     *       {@code pulse://{tenantSlug}/{domainSlug}/tables/{layer}/{tableSlug}/ready}</li>
     *   <li>{@code pipeline_event} → returns {@code null} (requires
     *       {@link com.pulse.pipeline.service.OrchestrationNamespaceService}
     *       to resolve the upstream pipeline's complete URI)</li>
     * </ul>
     *
     * @param tenantSlug the tenant slug for URI scoping
     * @return the dataset URI, or {@code null} when resolution requires
     *         external context
     */
    public String toDatasetUri(String tenantSlug) {
        if ("table_ready".equals(eventType) && tableRef != null) {
            return "pulse://" + tenantSlug + "/"
                    + tableRef.domainSlug() + "/tables/"
                    + tableRef.layer() + "/"
                    + tableRef.tableSlug() + "/ready";
        }
        // pipeline_event requires OrchestrationNamespace context — caller
        // must resolve via OrchestrationNamespaceService.
        return null;
    }

    // ------------------------------------------------------------------ serialisation

    /**
     * Convert this event ref to a plain map suitable for JSON
     * serialisation into version metadata.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventType", eventType);

        if (tableContractId != null) {
            map.put("tableContractId", tableContractId);
        }
        if (tableRef != null) {
            Map<String, Object> refMap = new LinkedHashMap<>();
            refMap.put("domainSlug", tableRef.domainSlug());
            refMap.put("layer", tableRef.layer());
            refMap.put("tableSlug", tableRef.tableSlug());
            map.put("tableRef", refMap);
        }
        if (pipelineId != null) {
            map.put("pipelineId", pipelineId);
        }
        if (eventSlug != null && !"pipeline_complete".equals(eventSlug)) {
            map.put("eventSlug", eventSlug);
        }
        if (description != null) {
            map.put("description", description);
        }
        return map;
    }
}
