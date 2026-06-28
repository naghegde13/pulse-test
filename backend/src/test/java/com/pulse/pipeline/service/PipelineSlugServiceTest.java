package com.pulse.pipeline.service;

import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.repository.PipelineRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipelineSlugServiceTest {

    @Mock
    private PipelineRepository pipelineRepository;

    @InjectMocks
    private PipelineSlugService pipelineSlugService;

    // ------------------------------------------------------------------ helpers

    private Pipeline buildPipeline(String id, String tenantId, String name, String domainId) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(id);
        pipeline.setTenantId(tenantId);
        pipeline.setName(name);
        pipeline.setDomainId(domainId);
        pipeline.setDomainName("Test Domain");
        pipeline.setCreatedBy("test-user");
        return pipeline;
    }

    // ------------------------------------------------------------------ assignSlug: immutability

    @Test
    @DisplayName("assignSlug returns existing slug unchanged when already set")
    void assignSlug_existingSlug_returnsUnchanged() {
        Pipeline pipeline = buildPipeline("pipe-001", "acme", "Customer Ingestion", "domain-1");
        pipeline.setPipelineSlug("my-stable-slug");

        String result = pipelineSlugService.assignSlug(pipeline);

        assertEquals("my-stable-slug", result);
        assertEquals("my-stable-slug", pipeline.getPipelineSlug());
        // Should NOT interact with the repository at all
        verifyNoInteractions(pipelineRepository);
    }

    // ------------------------------------------------------------------ assignSlug: compute from name

    @Test
    @DisplayName("assignSlug computes slug from name when no slug is set")
    void assignSlug_noSlug_computesFromName() {
        Pipeline pipeline = buildPipeline("pipe-002", "acme", "Customer Ingestion", "domain-1");
        when(pipelineRepository.findByTenantIdOrderByUpdatedAtDesc("acme"))
                .thenReturn(List.of());
        when(pipelineRepository.save(any(Pipeline.class))).thenReturn(pipeline);

        String result = pipelineSlugService.assignSlug(pipeline);

        assertEquals("customer-ingestion", result);
        assertEquals("customer-ingestion", pipeline.getPipelineSlug());
    }

    // ------------------------------------------------------------------ assignSlug: persistence

    @Test
    @DisplayName("assignSlug persists the computed slug via repository save")
    void assignSlug_persistsSlug() {
        Pipeline pipeline = buildPipeline("pipe-003", "acme", "Data Sync", "domain-1");
        when(pipelineRepository.findByTenantIdOrderByUpdatedAtDesc("acme"))
                .thenReturn(List.of());
        when(pipelineRepository.save(any(Pipeline.class))).thenReturn(pipeline);

        pipelineSlugService.assignSlug(pipeline);

        assertEquals("data-sync", pipeline.getPipelineSlug());
        verify(pipelineRepository).save(pipeline);
    }

    // ------------------------------------------------------------------ assignSlug: collision

    @Test
    @DisplayName("assignSlug appends collision suffix when slug already taken in same domain")
    void assignSlug_collision_appendsSuffix() {
        Pipeline existing = buildPipeline("pipe-100", "acme", "Data Sync", "domain-1");
        existing.setPipelineSlug("data-sync");

        Pipeline pipeline = buildPipeline("pipe-200", "acme", "Data Sync", "domain-1");
        when(pipelineRepository.findByTenantIdOrderByUpdatedAtDesc("acme"))
                .thenReturn(List.of(existing));
        when(pipelineRepository.save(any(Pipeline.class))).thenReturn(pipeline);

        String result = pipelineSlugService.assignSlug(pipeline);

        String expectedSuffix = OrchestrationNamespaceService.generateCollisionSuffix("pipe-200");
        assertEquals("data-sync" + expectedSuffix, result);
        assertEquals(result, pipeline.getPipelineSlug());
        verify(pipelineRepository).save(pipeline);
    }

    @Test
    @DisplayName("assignSlug does NOT append collision suffix when same slug is in different domain")
    void assignSlug_noCollision_differentDomain() {
        Pipeline existing = buildPipeline("pipe-100", "acme", "Data Sync", "domain-OTHER");
        existing.setPipelineSlug("data-sync");

        Pipeline pipeline = buildPipeline("pipe-200", "acme", "Data Sync", "domain-1");
        when(pipelineRepository.findByTenantIdOrderByUpdatedAtDesc("acme"))
                .thenReturn(List.of(existing));
        when(pipelineRepository.save(any(Pipeline.class))).thenReturn(pipeline);

        String result = pipelineSlugService.assignSlug(pipeline);

        assertEquals("data-sync", result, "No suffix when collision is in a different domain");
    }

    // ------------------------------------------------------------------ slugify

    @Test
    @DisplayName("slugify lowercases and replaces special chars with hyphens")
    void slugify_lowercasesAndHyphenates() {
        assertEquals("hello-world", PipelineSlugService.slugify("Hello World"));
        assertEquals("my-pipeline-v2", PipelineSlugService.slugify("My Pipeline (v2)"));
        assertEquals("abc-123", PipelineSlugService.slugify("abc---123"));
        assertEquals("a-b-c", PipelineSlugService.slugify("a.b.c"));
    }

    @Test
    @DisplayName("slugify handles null, empty, and blank input")
    void slugify_handlesNullAndBlank() {
        assertEquals("unknown", PipelineSlugService.slugify(null));
        assertEquals("unknown", PipelineSlugService.slugify(""));
        assertEquals("unknown", PipelineSlugService.slugify("   "));
    }

    @Test
    @DisplayName("slugify truncates at MAX_SLUG_LENGTH (48 chars)")
    void slugify_truncatesAtMaxLength() {
        String longName = "this-is-a-very-long-pipeline-name-that-exceeds-the-maximum-slug-length-limit";
        String slug = PipelineSlugService.slugify(longName);

        assertTrue(slug.length() <= 48,
                "Slug length " + slug.length() + " exceeds max of 48");
        assertFalse(slug.endsWith("-"), "Slug should not end with a dash after truncation");
    }

    @Test
    @DisplayName("slugify trims leading and trailing dashes")
    void slugify_trimsLeadingAndTrailingDashes() {
        assertEquals("test", PipelineSlugService.slugify("test!!!"));
        assertEquals("middle", PipelineSlugService.slugify("---middle---"));
    }
}
