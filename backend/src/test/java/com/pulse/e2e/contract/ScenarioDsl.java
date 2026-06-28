package com.pulse.e2e.contract;

import java.util.List;
import java.util.Map;

/**
 * Declarative boundary for API-first E2E scenarios.
 * Keeps scenario authors on stable contracts while builder/runtime lanes
 * evolve underneath.
 */
public final class ScenarioDsl {

    private ScenarioDsl() {
    }

    public enum ProofMode {
        ARTIFACT_ONLY,
        STATIC_DEPLOYABILITY,
        LIVE_RUNTIME
    }

    public enum RuntimeAdapter {
        LOCAL_AIRFLOW_BRIDGE,
        GCP_COMPOSER_DATAPROC_BRIDGE
    }

    public record ScenarioDefinition(
            String scenarioId,
            String displayName,
            ProofMode proofMode,
            RuntimeAdapter runtimeAdapter,
            List<String> featureTags,
            BuilderPlan builderPlan,
            EvidenceExpectation evidenceExpectation,
            Map<String, Object> fixtureRefs
    ) {
        public ScenarioDefinition {
            featureTags = featureTags == null ? List.of() : List.copyOf(featureTags);
            fixtureRefs = fixtureRefs == null ? Map.of() : Map.copyOf(fixtureRefs);
        }
    }

    public record BuilderPlan(
            String tenantId,
            String domainSlug,
            String sourceDataset,
            List<String> blueprintKeys,
            String fixtureId
    ) {
        public BuilderPlan {
            blueprintKeys = blueprintKeys == null ? List.of() : List.copyOf(blueprintKeys);
        }
    }

    public record EvidenceExpectation(
            List<String> requiredArtifacts,
            List<String> requiredEvidenceTypes,
            String verdictFile,
            List<String> requiredLayerIds
    ) {
        public EvidenceExpectation(List<String> requiredArtifacts,
                                   List<String> requiredEvidenceTypes,
                                   String verdictFile) {
            this(requiredArtifacts, requiredEvidenceTypes, verdictFile, List.of());
        }

        public EvidenceExpectation {
            requiredArtifacts = requiredArtifacts == null ? List.of() : List.copyOf(requiredArtifacts);
            requiredEvidenceTypes = requiredEvidenceTypes == null ? List.of() : List.copyOf(requiredEvidenceTypes);
            requiredLayerIds = requiredLayerIds == null ? List.of() : List.copyOf(requiredLayerIds);
        }
    }
}
