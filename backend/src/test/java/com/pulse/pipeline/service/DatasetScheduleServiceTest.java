package com.pulse.pipeline.service;

import com.pulse.pipeline.model.EventRef;
import com.pulse.pipeline.model.EventRef.TableRefPreview;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.service.OrchestrationNamespaceService.OrchestrationNamespace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatasetScheduleServiceTest {

    @Mock
    private PipelineRepository pipelineRepository;

    @Mock
    private OrchestrationNamespaceService orchestrationNamespaceService;

    @InjectMocks
    private DatasetScheduleService datasetScheduleService;

    // ------------------------------------------------------------------ helpers

    private PipelineVersion versionWithPolicy(Map<String, Object> policy) {
        PipelineVersion version = new PipelineVersion();
        version.setMetadata(Map.of("orchestrationPolicy", policy));
        return version;
    }

    // ------------------------------------------------------------------ resolveEventRefs

    @Test
    @DisplayName("resolveEventRefs parses dataset_event refs from version metadata")
    void resolveEventRefs_datasetEvent_parsesRefs() {
        Map<String, Object> tableRef = Map.of(
                "domainSlug", "finance",
                "layer", "curated",
                "tableSlug", "gl_balances"
        );
        Map<String, Object> ref1 = Map.of(
                "eventType", "table_ready",
                "tableContractId", "tc-001",
                "tableRef", tableRef,
                "description", "GL balances ready"
        );
        Map<String, Object> ref2 = Map.of(
                "eventType", "pipeline_event",
                "pipelineId", "pipe-upstream-001",
                "eventSlug", "pipeline_complete"
        );

        Map<String, Object> policy = Map.of(
                "scheduleType", "dataset_event",
                "event_refs", List.of(ref1, ref2)
        );

        PipelineVersion version = versionWithPolicy(policy);
        List<EventRef> refs = datasetScheduleService.resolveEventRefs(version);

        assertEquals(2, refs.size());

        EventRef first = refs.get(0);
        assertEquals("table_ready", first.eventType());
        assertEquals("tc-001", first.tableContractId());
        assertNotNull(first.tableRef());
        assertEquals("finance", first.tableRef().domainSlug());
        assertEquals("curated", first.tableRef().layer());
        assertEquals("gl_balances", first.tableRef().tableSlug());
        assertEquals("GL balances ready", first.description());

        EventRef second = refs.get(1);
        assertEquals("pipeline_event", second.eventType());
        assertEquals("pipe-upstream-001", second.pipelineId());
        assertEquals("pipeline_complete", second.eventSlug());
    }

    @Test
    @DisplayName("resolveEventRefs returns empty list for cron schedule type")
    void resolveEventRefs_cronSchedule_returnsEmpty() {
        Map<String, Object> policy = Map.of("scheduleType", "cron");
        PipelineVersion version = versionWithPolicy(policy);

        List<EventRef> refs = datasetScheduleService.resolveEventRefs(version);

        assertTrue(refs.isEmpty());
    }

    @Test
    @DisplayName("resolveEventRefs returns empty list when no metadata")
    void resolveEventRefs_noPolicy_returnsEmpty() {
        PipelineVersion version = new PipelineVersion();
        version.setMetadata(null);

        List<EventRef> refs = datasetScheduleService.resolveEventRefs(version);

        assertTrue(refs.isEmpty());
    }

    @Test
    @DisplayName("resolveEventRefs returns empty list when metadata has no orchestrationPolicy")
    void resolveEventRefs_noPolicyKey_returnsEmpty() {
        PipelineVersion version = new PipelineVersion();
        version.setMetadata(Map.of("someOtherKey", "value"));

        List<EventRef> refs = datasetScheduleService.resolveEventRefs(version);

        assertTrue(refs.isEmpty());
    }

    // ------------------------------------------------------------------ resolveDatasetUris

    @Test
    @DisplayName("resolveDatasetUris generates correct URIs for table_ready refs")
    void resolveDatasetUris_generatesCorrectUris() {
        EventRef tableRef = new EventRef(
                "table_ready", "tc-001",
                new TableRefPreview("finance", "curated", "gl_balances"),
                null, null, null
        );
        EventRef pipelineRef = new EventRef(
                "pipeline_event", null, null,
                "pipe-upstream-001", "pipeline_complete", null
        );

        Pipeline upstreamPipeline = new Pipeline();
        upstreamPipeline.setTenantId("acme");
        upstreamPipeline.setDomainName("Finance");
        upstreamPipeline.setName("Upstream Pipeline");
        upstreamPipeline.setCreatedBy("test");

        OrchestrationNamespace ns = new OrchestrationNamespace(
                "acme", "finance", "upstream-pipeline",
                "pulse__acme__finance__upstream-pipeline",
                "pulse__acme__finance__upstream-pipeline",
                "finance/pipelines/upstream-pipeline",
                "pulse://acme/finance/pipelines/upstream-pipeline/events/pipeline_complete",
                Map.of()
        );

        when(pipelineRepository.findById("pipe-upstream-001")).thenReturn(Optional.of(upstreamPipeline));
        when(orchestrationNamespaceService.resolve(any(Pipeline.class))).thenReturn(ns);

        List<String> uris = datasetScheduleService.resolveDatasetUris(
                List.of(tableRef, pipelineRef), "acme");

        assertEquals(2, uris.size());
        assertEquals("pulse://acme/finance/tables/curated/gl_balances/ready", uris.get(0));
        assertEquals("pulse://acme/finance/pipelines/upstream-pipeline/events/pipeline_complete", uris.get(1));
    }

    // ------------------------------------------------------------------ validateEventRefs

    @Test
    @DisplayName("validateEventRefs reports error when table_ready has no tableRef or tableContractId")
    void validateEventRefs_missingTableRef_returnsError() {
        EventRef badRef = new EventRef("table_ready", null, null, null, null, null);

        List<Map<String, Object>> errors = datasetScheduleService.validateEventRefs(List.of(badRef));

        assertEquals(1, errors.size());
        assertEquals(0, errors.get(0).get("index"));
        assertEquals("tableRef", errors.get(0).get("field"));
    }

    @Test
    @DisplayName("validateEventRefs reports error when pipeline_event has no pipelineId")
    void validateEventRefs_missingPipelineId_returnsError() {
        EventRef badRef = new EventRef("pipeline_event", null, null, null, null, null);

        List<Map<String, Object>> errors = datasetScheduleService.validateEventRefs(List.of(badRef));

        assertEquals(1, errors.size());
        assertEquals(0, errors.get(0).get("index"));
        assertEquals("pipelineId", errors.get(0).get("field"));
    }

    @Test
    @DisplayName("validateEventRefs reports error for invalid eventType")
    void validateEventRefs_invalidEventType_returnsError() {
        EventRef badRef = new EventRef("unknown_type", null, null, null, null, null);

        List<Map<String, Object>> errors = datasetScheduleService.validateEventRefs(List.of(badRef));

        assertEquals(1, errors.size());
        assertEquals("eventType", errors.get(0).get("field"));
    }

    @Test
    @DisplayName("validateEventRefs returns empty errors for valid refs")
    void validateEventRefs_validRefs_returnsEmpty() {
        EventRef tableRef = new EventRef(
                "table_ready", "tc-001",
                new TableRefPreview("finance", "curated", "gl_balances"),
                null, null, null
        );
        EventRef pipelineRef = new EventRef(
                "pipeline_event", null, null,
                "pipe-001", "pipeline_complete", null
        );

        List<Map<String, Object>> errors = datasetScheduleService.validateEventRefs(
                List.of(tableRef, pipelineRef));

        assertTrue(errors.isEmpty());
    }
}
