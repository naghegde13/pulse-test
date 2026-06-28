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

@ExtendWith(MockitoExtension.class)
class OrchestrationNamespaceServiceTest {

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

    // ------------------------------------------------------------------ resolve

    @Test
    @DisplayName("resolve produces correct orchestration namespace from pipeline")
    void resolve_producesCorrectNamespace() {
        Pipeline pipeline = buildPipeline("pipe-001", "acme", "Customer Ingestion", "Finance");

        OrchestrationNamespace ns = orchestrationNamespaceService.resolve(pipeline);

        assertEquals("acme", ns.tenantSlug());
        assertEquals("finance", ns.domainSlug());
        assertEquals("customer-ingestion", ns.pipelineSlug());
        assertEquals("pulse__acme__finance__customer-ingestion", ns.logicalResourcePrefix());
        assertEquals("pulse__acme__finance__customer-ingestion", ns.logicalDagId());
        assertEquals("finance/pipelines/customer-ingestion", ns.logicalPackageNamespace());
        assertEquals("pulse://acme/finance/pipelines/customer-ingestion/events/pipeline_complete",
                ns.pipelineCompleteUri());
        assertNotNull(ns.tableReadyUris());
    }

    // ------------------------------------------------------------------ default domain

    @Test
    @DisplayName("resolve uses 'default' domain when domainName is null")
    void resolve_defaultDomain_whenDomainNull() {
        Pipeline pipeline = buildPipeline("pipe-002", "globex", "Data Sync", null);

        OrchestrationNamespace ns = orchestrationNamespaceService.resolve(pipeline);

        assertEquals("globex", ns.tenantSlug());
        assertEquals("default", ns.domainSlug());
        assertEquals("data-sync", ns.pipelineSlug());
        assertEquals("pulse__globex__default__data-sync", ns.logicalResourcePrefix());
    }

    @Test
    @DisplayName("resolve uses 'default' domain when domainName is blank")
    void resolve_defaultDomain_whenDomainBlank() {
        Pipeline pipeline = buildPipeline("pipe-003", "globex", "Data Sync", "  ");

        OrchestrationNamespace ns = orchestrationNamespaceService.resolve(pipeline);

        assertEquals("default", ns.domainSlug());
    }

    // ------------------------------------------------------------------ preview

    @Test
    @DisplayName("preview produces correct namespace from raw inputs")
    void preview_producesCorrectNamespace() {
        OrchestrationNamespace ns = orchestrationNamespaceService.preview(
                "acme", "Risk", "Daily Risk Calc");

        assertEquals("acme", ns.tenantSlug());
        assertEquals("risk", ns.domainSlug());
        assertEquals("daily-risk-calc", ns.pipelineSlug());
        assertEquals("pulse__acme__risk__daily-risk-calc", ns.logicalResourcePrefix());
        assertEquals("pulse__acme__risk__daily-risk-calc", ns.logicalDagId());
        assertEquals("risk/pipelines/daily-risk-calc", ns.logicalPackageNamespace());
        assertEquals("pulse://acme/risk/pipelines/daily-risk-calc/events/pipeline_complete",
                ns.pipelineCompleteUri());
    }

    // ------------------------------------------------------------------ slugify

    @Test
    @DisplayName("slugify lowercases and replaces special chars with dashes")
    void slugify_lowercasesAndReplacesSpecialChars() {
        assertEquals("hello-world", OrchestrationNamespaceService.slugify("Hello World"));
        assertEquals("my-pipeline-v2", OrchestrationNamespaceService.slugify("My Pipeline (v2)"));
        assertEquals("unknown", OrchestrationNamespaceService.slugify(null));
        assertEquals("unknown", OrchestrationNamespaceService.slugify(""));
        assertEquals("unknown", OrchestrationNamespaceService.slugify("   "));
        assertEquals("abc-123", OrchestrationNamespaceService.slugify("abc---123"));
        assertEquals("test", OrchestrationNamespaceService.slugify("test!!!"));
        assertEquals("a-b-c", OrchestrationNamespaceService.slugify("a.b.c"));
    }

    // ------------------------------------------------------------------ truncation

    @Test
    @DisplayName("slugify truncates at MAX_SLUG_LENGTH (48 chars)")
    void slugify_truncatesAtMaxLength() {
        String longName = "this-is-a-very-long-pipeline-name-that-exceeds-the-maximum-slug-length-limit";
        String slug = OrchestrationNamespaceService.slugify(longName);

        assertTrue(slug.length() <= 48,
                "Slug length " + slug.length() + " exceeds max of 48");
        assertFalse(slug.endsWith("-"), "Slug should not end with a dash after truncation");
    }

    // ------------------------------------------------------------------ collision suffix

    @Test
    @DisplayName("generateCollisionSuffix produces stable suffix for same pipelineId")
    void generateCollisionSuffix_producesStableSuffix() {
        String suffix1 = OrchestrationNamespaceService.generateCollisionSuffix("pipe-001");
        String suffix2 = OrchestrationNamespaceService.generateCollisionSuffix("pipe-001");

        assertEquals(suffix1, suffix2, "Same pipelineId should produce same suffix");
        assertTrue(suffix1.startsWith("-p"), "Suffix should start with '-p'");
        assertEquals(10, suffix1.length(), "Suffix should be '-p' + 8 hex chars = 10 chars");
    }

    @Test
    @DisplayName("generateCollisionSuffix produces different suffixes for different IDs")
    void generateCollisionSuffix_producesDifferentSuffixesForDifferentIds() {
        String suffix1 = OrchestrationNamespaceService.generateCollisionSuffix("pipe-001");
        String suffix2 = OrchestrationNamespaceService.generateCollisionSuffix("pipe-002");

        assertNotEquals(suffix1, suffix2);
    }

    // ------------------------------------------------------------------ special chars in tenant/domain

    @Test
    @DisplayName("resolve handles special characters in tenant and domain names")
    void resolve_handlesSpecialCharsInNames() {
        Pipeline pipeline = buildPipeline("pipe-004", "ACME Corp.", "My Pipeline!!", "Finance & Risk");

        OrchestrationNamespace ns = orchestrationNamespaceService.resolve(pipeline);

        assertEquals("acme-corp", ns.tenantSlug());
        assertEquals("finance-risk", ns.domainSlug());
        assertEquals("my-pipeline", ns.pipelineSlug());
    }
}
