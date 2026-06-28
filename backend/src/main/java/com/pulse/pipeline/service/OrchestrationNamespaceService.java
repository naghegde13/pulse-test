package com.pulse.pipeline.service;

import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.repository.PipelineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

@Service
public class OrchestrationNamespaceService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationNamespaceService.class);
    private static final int MAX_SLUG_LENGTH = 48;

    private final PipelineRepository pipelineRepository;

    public OrchestrationNamespaceService(PipelineRepository pipelineRepository) {
        this.pipelineRepository = pipelineRepository;
    }

    // ------------------------------------------------------------------ records

    public record OrchestrationNamespace(
            String tenantSlug,
            String domainSlug,
            String pipelineSlug,
            String logicalResourcePrefix,
            String logicalDagId,
            String logicalPackageNamespace,
            String pipelineCompleteUri,
            Map<String, String> tableReadyUris
    ) {
    }

    // ------------------------------------------------------------------ public API

    public OrchestrationNamespace resolve(Pipeline pipeline) {
        String tenantSlug = slugify(pipeline.getTenantId());
        String domainSlug = slugify(
                pipeline.getDomainName() != null && !pipeline.getDomainName().isBlank()
                        ? pipeline.getDomainName() : "default");
        // ARCH-007: prefer persisted immutable slug over re-slugifying mutable name
        String pipelineSlug = pipeline.getPipelineSlug() != null && !pipeline.getPipelineSlug().isBlank()
                ? pipeline.getPipelineSlug()
                : slugify(pipeline.getName());

        return buildNamespace(tenantSlug, domainSlug, pipelineSlug);
    }

    public OrchestrationNamespace preview(String tenantId, String domainName, String proposedPipelineName) {
        String tenantSlug = slugify(tenantId);
        String domainSlug = slugify(
                domainName != null && !domainName.isBlank() ? domainName : "default");
        String pipelineSlug = slugify(proposedPipelineName);

        return buildNamespace(tenantSlug, domainSlug, pipelineSlug);
    }

    // ------------------------------------------------------------------ internal

    private OrchestrationNamespace buildNamespace(String tenantSlug, String domainSlug, String pipelineSlug) {
        String logicalResourcePrefix = "pulse__" + tenantSlug + "__" + domainSlug + "__" + pipelineSlug;
        String logicalDagId = logicalResourcePrefix;
        String logicalPackageNamespace = domainSlug + "/pipelines/" + pipelineSlug;
        String pipelineCompleteUri = "pulse://" + tenantSlug + "/" + domainSlug
                + "/pipelines/" + pipelineSlug + "/events/pipeline_complete";

        log.debug("Resolved orchestration namespace: prefix={}, dagId={}, packageNs={}",
                logicalResourcePrefix, logicalDagId, logicalPackageNamespace);

        return new OrchestrationNamespace(
                tenantSlug,
                domainSlug,
                pipelineSlug,
                logicalResourcePrefix,
                logicalDagId,
                logicalPackageNamespace,
                pipelineCompleteUri,
                Map.of()
        );
    }

    // ------------------------------------------------------------------ helpers

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
            // Avoid trailing dash after truncation
            trimmed = trimmed.replaceAll("-$", "");
        }
        return trimmed;
    }

    static String generateCollisionSuffix(String pipelineId) {
        String hash = sha256(pipelineId != null ? pipelineId : "");
        return "-p" + hash.substring(0, 8);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
