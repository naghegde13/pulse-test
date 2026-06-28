package com.pulse.e2e.contract;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class EvidenceContracts {

    private EvidenceContracts() {
    }

    public enum Verdict {
        PASS,
        FAIL,
        FLAKY,
        INFRA_BLOCKED
    }

    public record EvidenceArtifact(
            String artifactId,
            String type,
            Path path,
            String sha256,
            String producingAdapter,
            String retentionPolicy,
            Map<String, Object> metadata
    ) {
        public EvidenceArtifact {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record LayerVerdict(
            String layerId,
            Verdict verdict,
            List<String> failureCodes,
            Map<String, Object> details
    ) {
        public LayerVerdict {
            if (layerId == null || layerId.isBlank()) {
                throw new IllegalArgumentException("layerId is required");
            }
            verdict = verdict == null ? Verdict.FAIL : verdict;
            failureCodes = failureCodes == null ? List.of() : List.copyOf(failureCodes);
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    public record EvidenceBundle(
            String scenarioId,
            String generationRunId,
            Path root,
            List<EvidenceArtifact> artifacts,
            Map<String, Object> summary
    ) {
        public EvidenceBundle {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            summary = summary == null ? Map.of() : Map.copyOf(summary);
        }
    }
}
