package com.pulse.pipeline.service;

import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.service.OrchestrationNamespaceService.OrchestrationNamespace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ARCH-007: Verifies that OrchestrationNamespaceService.resolve() correctly
 * prefers the persisted pipeline slug over re-slugifying the mutable name.
 */
@ExtendWith(MockitoExtension.class)
class OrchestrationNamespaceSlugIntegrationTest {

    @Mock
    private PipelineRepository pipelineRepository;

    @InjectMocks
    private OrchestrationNamespaceService orchestrationNamespaceService;

    // ------------------------------------------------------------------ helpers

    private Pipeline buildPipeline(String id, String tenantId, String name, String domainName) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(id);
        pipeline.setTenantId(tenantId);
        pipeline.setName(name);
        pipeline.setDomainName(domainName);
        pipeline.setCreatedBy("test-user");
        return pipeline;
    }

    // ------------------------------------------------------------------ slug preference

    @Test
    @DisplayName("resolve prefers pipelineSlug over slugified name")
    void resolve_prefersPipelineSlugOverName() {
        Pipeline pipeline = buildPipeline("pipe-001", "acme", "Some Different Name", "Finance");
        pipeline.setPipelineSlug("my-stable-slug");

        OrchestrationNamespace ns = orchestrationNamespaceService.resolve(pipeline);

        assertEquals("my-stable-slug", ns.pipelineSlug(),
                "Should use the persisted pipelineSlug, not a slugified version of the name");
        assertTrue(ns.logicalDagId().contains("my-stable-slug"),
                "logicalDagId should contain the persisted slug");
        assertFalse(ns.logicalDagId().contains("some-different-name"),
                "logicalDagId should NOT contain the slugified name");
        assertEquals("pulse__acme__finance__my-stable-slug", ns.logicalDagId());
        assertEquals("finance/pipelines/my-stable-slug", ns.logicalPackageNamespace());
    }

    // ------------------------------------------------------------------ fallback to name

    @Test
    @DisplayName("resolve falls back to slugified name when pipelineSlug is null")
    void resolve_fallsBackToNameWhenNoSlug() {
        Pipeline pipeline = buildPipeline("pipe-002", "acme", "Customer Ingestion", "Finance");
        // pipelineSlug is null by default

        OrchestrationNamespace ns = orchestrationNamespaceService.resolve(pipeline);

        assertEquals("customer-ingestion", ns.pipelineSlug(),
                "Should fall back to slugified name when pipelineSlug is null");
        assertEquals("pulse__acme__finance__customer-ingestion", ns.logicalDagId());
    }

    @Test
    @DisplayName("resolve falls back to slugified name when pipelineSlug is blank")
    void resolve_blankSlugFallsBackToName() {
        Pipeline pipeline = buildPipeline("pipe-003", "acme", "Data Sync", "Risk");
        pipeline.setPipelineSlug("   ");

        OrchestrationNamespace ns = orchestrationNamespaceService.resolve(pipeline);

        assertEquals("data-sync", ns.pipelineSlug(),
                "Should fall back to slugified name when pipelineSlug is blank");
        assertEquals("pulse__acme__risk__data-sync", ns.logicalDagId());
    }

    @Test
    @DisplayName("resolve falls back to slugified name when pipelineSlug is empty string")
    void resolve_emptySlugFallsBackToName() {
        Pipeline pipeline = buildPipeline("pipe-004", "globex", "ETL Pipeline", "Operations");
        pipeline.setPipelineSlug("");

        OrchestrationNamespace ns = orchestrationNamespaceService.resolve(pipeline);

        assertEquals("etl-pipeline", ns.pipelineSlug(),
                "Should fall back to slugified name when pipelineSlug is empty string");
        assertEquals("pulse__globex__operations__etl-pipeline", ns.logicalDagId());
    }

    // ------------------------------------------------------------------ stable DAG identity

    @Test
    @DisplayName("resolve produces stable dagId even when pipeline name changes")
    void resolve_stableDagId_afterNameChange() {
        Pipeline pipeline = buildPipeline("pipe-005", "acme", "Original Name", "Finance");
        pipeline.setPipelineSlug("original-name");

        OrchestrationNamespace ns1 = orchestrationNamespaceService.resolve(pipeline);

        // Simulate a name change — slug must remain stable
        pipeline.setName("Completely Renamed Pipeline");
        OrchestrationNamespace ns2 = orchestrationNamespaceService.resolve(pipeline);

        assertEquals(ns1.logicalDagId(), ns2.logicalDagId(),
                "DAG ID must remain stable across pipeline renames when slug is set");
        assertEquals(ns1.pipelineSlug(), ns2.pipelineSlug(),
                "Pipeline slug must not change when name changes");
    }
}
