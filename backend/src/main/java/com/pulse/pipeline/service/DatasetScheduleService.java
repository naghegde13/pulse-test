package com.pulse.pipeline.service;

import com.pulse.pipeline.model.EventRef;
import com.pulse.pipeline.model.EventRef.TableRefPreview;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.repository.PipelineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * ARCH-007 — Resolves and validates dataset-event scheduling references
 * stored in pipeline version metadata.
 *
 * <p>When a pipeline version's {@code orchestrationPolicy.scheduleType}
 * is {@code dataset_event}, this service reads the companion
 * {@code event_refs} array and converts them into typed
 * {@link EventRef} records.</p>
 */
@Service
public class DatasetScheduleService {

    private static final Logger log = LoggerFactory.getLogger(DatasetScheduleService.class);

    private final OrchestrationNamespaceService orchestrationNamespaceService;
    private final PipelineRepository pipelineRepository;

    public DatasetScheduleService(OrchestrationNamespaceService orchestrationNamespaceService,
                                  PipelineRepository pipelineRepository) {
        this.orchestrationNamespaceService = orchestrationNamespaceService;
        this.pipelineRepository = pipelineRepository;
    }

    // ------------------------------------------------------------------ public API

    /**
     * Parse {@code event_refs} from version metadata when the schedule
     * type is {@code dataset_event}.
     *
     * @return parsed event refs, or empty list when schedule type is not
     *         dataset_event or metadata is absent
     */
    public List<EventRef> resolveEventRefs(PipelineVersion version) {
        Map<String, Object> metadata = version.getMetadata();
        if (metadata == null) {
            return List.of();
        }

        Object policyRaw = metadata.get("orchestrationPolicy");
        if (!(policyRaw instanceof Map<?, ?> policy)) {
            return List.of();
        }

        Object scheduleType = policy.get("scheduleType");
        if (!"dataset_event".equals(scheduleType)) {
            return List.of();
        }

        Object refsRaw = policy.get("event_refs");
        if (!(refsRaw instanceof List<?> refsList)) {
            log.warn("orchestrationPolicy.scheduleType is dataset_event but event_refs is missing or not a list");
            return List.of();
        }

        List<EventRef> result = new ArrayList<>();
        for (Object entry : refsList) {
            if (entry instanceof Map<?, ?> refMap) {
                result.add(parseEventRef(refMap));
            }
        }

        log.debug("Resolved {} event refs for version {}", result.size(), version.getId());
        return Collections.unmodifiableList(result);
    }

    /**
     * Map each {@link EventRef} to its canonical dataset URI.
     *
     * @param refs       event refs to resolve
     * @param tenantSlug tenant slug for URI scoping
     * @return list of dataset URIs (nulls from pipeline_event refs are
     *         filtered out)
     */
    public List<String> resolveDatasetUris(List<EventRef> refs, String tenantSlug) {
        return refs.stream()
                .map(ref -> resolveUri(ref, tenantSlug))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Validate a list of event refs and return any errors found.
     *
     * @return list of error maps (empty = valid). Each error contains
     *         {@code index}, {@code field}, and {@code message}.
     */
    public List<Map<String, Object>> validateEventRefs(List<EventRef> refs) {
        List<Map<String, Object>> errors = new ArrayList<>();

        for (int i = 0; i < refs.size(); i++) {
            EventRef ref = refs.get(i);

            if (ref.eventType() == null
                    || (!"table_ready".equals(ref.eventType()) && !"pipeline_event".equals(ref.eventType()))) {
                errors.add(validationError(i, "eventType",
                        "eventType must be 'table_ready' or 'pipeline_event'"));
                continue;
            }

            if ("table_ready".equals(ref.eventType())) {
                if (ref.tableContractId() == null && ref.tableRef() == null) {
                    errors.add(validationError(i, "tableRef",
                            "table_ready event must have either tableContractId or tableRef"));
                }
            }

            if ("pipeline_event".equals(ref.eventType())) {
                if (ref.pipelineId() == null || ref.pipelineId().isBlank()) {
                    errors.add(validationError(i, "pipelineId",
                            "pipeline_event must have a pipelineId"));
                }
            }
        }

        return errors;
    }

    // ------------------------------------------------------------------ internal

    private EventRef parseEventRef(Map<?, ?> refMap) {
        String eventType = asString(refMap.get("eventType"));
        String tableContractId = asString(refMap.get("tableContractId"));
        String pipelineId = asString(refMap.get("pipelineId"));
        String eventSlug = asString(refMap.get("eventSlug"));
        String description = asString(refMap.get("description"));

        TableRefPreview tableRef = null;
        Object tableRefRaw = refMap.get("tableRef");
        if (tableRefRaw instanceof Map<?, ?> trMap) {
            tableRef = new TableRefPreview(
                    asString(trMap.get("domainSlug")),
                    asString(trMap.get("layer")),
                    asString(trMap.get("tableSlug"))
            );
        }

        return new EventRef(eventType, tableContractId, tableRef, pipelineId, eventSlug, description);
    }

    private String resolveUri(EventRef ref, String tenantSlug) {
        // table_ready can resolve directly from the ref
        String uri = ref.toDatasetUri(tenantSlug);
        if (uri != null) {
            return uri;
        }

        // pipeline_event requires namespace resolution via the upstream pipeline
        if ("pipeline_event".equals(ref.eventType()) && ref.pipelineId() != null) {
            return pipelineRepository.findById(ref.pipelineId())
                    .map(orchestrationNamespaceService::resolve)
                    .map(OrchestrationNamespaceService.OrchestrationNamespace::pipelineCompleteUri)
                    .orElse(null);
        }

        return null;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static Map<String, Object> validationError(int index, String field, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("index", index);
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}
