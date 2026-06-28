package com.pulse.pipeline.service;

import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.repository.PipelineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * ARCH-007: Manages pipeline slug assignment and immutability.
 * <p>
 * Once a slug is assigned it is immutable — subsequent calls to {@link #assignSlug}
 * return the existing value without modification.  New slugs are derived from
 * {@link Pipeline#getName()} via {@link #slugify(String)}, with a collision suffix
 * appended when another pipeline in the same tenant+domain already owns the slug.
 */
@Service
public class PipelineSlugService {

    private static final Logger log = LoggerFactory.getLogger(PipelineSlugService.class);
    private static final int MAX_SLUG_LENGTH = 48;

    private final PipelineRepository pipelineRepository;

    public PipelineSlugService(PipelineRepository pipelineRepository) {
        this.pipelineRepository = pipelineRepository;
    }

    // ------------------------------------------------------------------ public API

    /**
     * Assign or return the immutable pipeline slug.
     *
     * @return the (possibly newly-computed) slug
     */
    public String assignSlug(Pipeline pipeline) {
        // Immutable: if already set, return unchanged
        if (pipeline.getPipelineSlug() != null && !pipeline.getPipelineSlug().isBlank()) {
            log.debug("Pipeline {} already has slug '{}' — returning unchanged",
                    pipeline.getId(), pipeline.getPipelineSlug());
            return pipeline.getPipelineSlug();
        }

        String slug = slugify(pipeline.getName());

        // Check for collision within the same tenant + domain scope
        if (hasCollision(pipeline.getTenantId(), pipeline.getDomainId(), slug, pipeline.getId())) {
            String suffix = OrchestrationNamespaceService.generateCollisionSuffix(pipeline.getId());
            log.info("Slug collision for '{}' in tenant={}, domain={} — appending suffix {}",
                    slug, pipeline.getTenantId(), pipeline.getDomainId(), suffix);
            slug = slug + suffix;
            // Ensure we don't exceed max length after suffix
            if (slug.length() > MAX_SLUG_LENGTH) {
                slug = slug.substring(0, MAX_SLUG_LENGTH).replaceAll("-$", "");
            }
        }

        pipeline.setPipelineSlug(slug);
        pipelineRepository.save(pipeline);
        log.info("Assigned slug '{}' to pipeline {}", slug, pipeline.getId());
        return slug;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Slugify a name — same algorithm as {@link OrchestrationNamespaceService#slugify(String)}.
     * Lowercase, replace non-[a-z0-9] with hyphen, collapse runs, trim dashes, max 48 chars.
     */
    static String slugify(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        String lowered = name.toLowerCase();
        String dashed = lowered.replaceAll("[^a-z0-9]+", "-");
        String collapsed = dashed.replaceAll("-{2,}", "-");
        String trimmed = collapsed.replaceAll("^-|-$", "");
        if (trimmed.isEmpty()) {
            return "unknown";
        }
        if (trimmed.length() > MAX_SLUG_LENGTH) {
            trimmed = trimmed.substring(0, MAX_SLUG_LENGTH);
            trimmed = trimmed.replaceAll("-$", "");
        }
        return trimmed;
    }

    /**
     * Check whether another pipeline in the same tenant+domain already uses the given slug.
     */
    private boolean hasCollision(String tenantId, String domainId, String slug, String currentPipelineId) {
        List<Pipeline> tenantPipelines = pipelineRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
        return tenantPipelines.stream()
                .filter(p -> !Objects.equals(p.getId(), currentPipelineId))
                .filter(p -> Objects.equals(p.getDomainId(), domainId))
                .anyMatch(p -> slug.equals(p.getPipelineSlug()));
    }
}
