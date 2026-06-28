package com.pulse.secret.service;

import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.sor.model.ConnectorCredentialStrategy;
import com.pulse.sor.model.ConnectorDefinition;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.secret.model.SecretAuthorityMode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CredentialReadinessService {

    private final PipelineRepository pipelineRepo;
    private final PipelineVersionRepository pipelineVersionRepo;
    private final SubPipelineInstanceRepository instanceRepo;
    private final ConnectorInstanceRepository ciRepo;
    private final ConnectorDefinitionRepository connDefRepo;
    private final SystemOfRecordRepository sorRepo;
    private final CredentialProfileRepository credRepo;
    private final SecretAuthorityReadinessService secretAuthorityReadinessService;
    private final ConnectorCredentialStrategyClassifier strategyClassifier;

    public CredentialReadinessService(
            PipelineRepository pipelineRepo,
            PipelineVersionRepository pipelineVersionRepo,
            SubPipelineInstanceRepository instanceRepo,
            ConnectorInstanceRepository ciRepo,
            ConnectorDefinitionRepository connDefRepo,
            SystemOfRecordRepository sorRepo,
            CredentialProfileRepository credRepo,
            SecretAuthorityReadinessService secretAuthorityReadinessService,
            ConnectorCredentialStrategyClassifier strategyClassifier) {
        this.pipelineRepo = pipelineRepo;
        this.pipelineVersionRepo = pipelineVersionRepo;
        this.instanceRepo = instanceRepo;
        this.ciRepo = ciRepo;
        this.connDefRepo = connDefRepo;
        this.sorRepo = sorRepo;
        this.credRepo = credRepo;
        this.secretAuthorityReadinessService = secretAuthorityReadinessService;
        this.strategyClassifier = strategyClassifier;
    }

    public Map<String, Object> compute(String pipelineId, String environment) {
        return compute(pipelineId, environment, null);
    }

    public Map<String, Object> compute(String pipelineId, String environment, String tenantId) {
        pipelineRepo.findById(pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", pipelineId));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("pipelineId", pipelineId);
        response.put("environment", environment);

        // Include secret authority mode information — fail closed.
        // Only report PROVEN when tenantId is available AND computeForTenant confirms it.
        if (tenantId != null && !tenantId.isBlank()) {
            var readiness = secretAuthorityReadinessService.computeForTenant(tenantId);
            response.put("secretAuthorityMode", readiness.mode().name());
            response.put("secretAuthorityProofStatus", readiness.proofStatus().name());
        } else {
            SecretAuthorityMode mode = secretAuthorityReadinessService.resolveMode();
            response.put("secretAuthorityMode", mode.name());
            // Without tenantId we cannot verify tenant credential state — fail closed.
            response.put("secretAuthorityProofStatus",
                    mode == SecretAuthorityMode.LOCAL_STUB ? "NON_PROOF" : "BLOCKED");
        }

        Optional<PipelineVersion> latestVersion = pipelineVersionRepo.findFirstByPipelineIdOrderByCreatedAtDesc(pipelineId);
        if (latestVersion.isEmpty()) {
            response.put("ready", true);
            response.put("connections", List.of());
            return response;
        }

        List<SubPipelineInstance> instances = instanceRepo.findByVersionIdOrderByExecutionOrderAsc(latestVersion.get().getId());
        List<Map<String, Object>> connections = new ArrayList<>();
        boolean ready = true;

        for (SubPipelineInstance instance : instances) {
            Map<String, Object> params = instance.getParams();
            Object cidObj = params == null ? null : params.get("connector_instance_id");
            if (!(cidObj instanceof String) || ((String) cidObj).isBlank()) {
                continue;
            }
            String connectorInstanceId = (String) cidObj;

            Optional<ConnectorInstance> ciOpt = ciRepo.findById(connectorInstanceId);
            if (ciOpt.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("connectorInstanceId", connectorInstanceId);
                row.put("connectorName", null);
                row.put("connectorTypeName", null);
                row.put("connectionKind", null);
                row.put("status", "MISSING_CONNECTOR");
                row.put("reason", "Connector instance " + connectorInstanceId + " not found");
                connections.add(row);
                ready = false;
                continue;
            }

            ConnectorInstance ci = ciOpt.get();
            Map<String, Object> row = buildConnectorReadinessRow(ci, environment, tenantId);
            connections.add(row);
            if (!isConnectionReady(row)) {
                ready = false;
            }
        }

        response.put("ready", ready);
        response.put("connections", connections);
        return response;
    }

    /**
     * LCT-047: strategy-aware readiness for a single connector instance in an
     * environment, using the SAME contract as the per-pipeline {@link #compute}
     * connections so the Add-Sink dialog and connector detail surfaces agree.
     * Tenant is resolved from the connector's SOR.
     */
    public Map<String, Object> computeForConnector(ConnectorInstance ci, String environment) {
        String tenantId = sorRepo.findById(ci.getSorId())
                .map(SystemOfRecord::getTenantId)
                .orElse(null);
        return buildConnectorReadinessRow(ci, environment, tenantId);
    }

    /** True when a connection row's status means it is ready to deploy. */
    private boolean isConnectionReady(Map<String, Object> row) {
        Object status = row.get("status");
        return "READY".equals(status) || "VALID".equals(status);
    }

    /**
     * Build the strategy-aware readiness row for a single connector instance.
     * Shape is identical to the per-connection rows surfaced by {@link #compute}.
     */
    private Map<String, Object> buildConnectorReadinessRow(ConnectorInstance ci, String environment, String tenantId) {
        String connectorInstanceId = ci.getId();
        Optional<ConnectorDefinition> defOpt = connDefRepo.findById(ci.getConnectorDefinitionId());
        String connectorTypeName = defOpt.map(ConnectorDefinition::getName).orElse(null);
        String connectionKind = sorRepo.findById(ci.getSorId())
                .map(this::resolveConnectionKind)
                .orElse("source");
        ConnectorCredentialStrategy strategy = strategyClassifier.resolveForInstance(ci);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("connectorInstanceId", connectorInstanceId);
        row.put("connectorName", ci.getName());
        row.put("connectorTypeName", connectorTypeName);
        row.put("connectionKind", connectionKind);
        row.put("credentialStrategy", strategy.name());

        if (strategy == ConnectorCredentialStrategy.INHERIT_TENANT_GCP_SERVICE_ACCOUNT) {
            // Lifecycle-file connector: readiness depends on tenant GCP credential
            // + storage scaffold, not connector-specific credential profiles.
            String resolvedTenantId = tenantId;
            if (resolvedTenantId == null || resolvedTenantId.isBlank()) {
                resolvedTenantId = sorRepo.findById(ci.getSorId())
                        .map(SystemOfRecord::getTenantId)
                        .orElse(null);
            }
            if (resolvedTenantId == null || resolvedTenantId.isBlank()) {
                row.put("status", "BLOCKED");
                row.put("reason", "Cannot resolve tenant for lifecycle-file readiness check");
            } else {
                Map<String, Object> lfReadiness = strategyClassifier.computeLifecycleFileReadiness(resolvedTenantId);
                boolean lfReady = Boolean.TRUE.equals(lfReadiness.get("ready"));
                row.put("status", lfReady ? "READY" : "BLOCKED");
                if (!lfReady) {
                    Object blockerList = lfReadiness.get("blockers");
                    row.put("reason", blockerList != null ? blockerList.toString() : "Lifecycle-file readiness not met");
                } else {
                    row.put("reason", null);
                }
                row.put("gcpCredentialStatus", lfReadiness.get("gcpCredentialStatus"));
                row.put("storageScaffoldStatus", lfReadiness.get("storageScaffoldStatus"));
            }
        } else {
            // Standard connector-specific credential flow
            Optional<CredentialProfile> profile = credRepo.findByConnectorInstanceIdAndEnvironment(connectorInstanceId, environment);

            if (profile.isEmpty()) {
                row.put("status", "MISSING");
                row.put("reason", "No credential profile exists for environment " + environment);
            } else {
                CredentialProfile cp = profile.get();
                String status = cp.getStatus().name();
                row.put("status", status);
                if ("SKIPPED".equals(status)) {
                    row.put("reason", "User skipped credential entry");
                } else if ("BLOCKED".equals(status)) {
                    row.put("reason", cp.getValidationReason() != null
                            ? cp.getValidationReason()
                            : "Credential is blocked; validate to see details");
                } else if ("FAILED".equals(status)) {
                    row.put("reason", cp.getValidationReason() != null
                            ? cp.getValidationReason()
                            : "Credential validation failed; retry validation");
                } else if ("INVALID".equals(status)) {
                    row.put("reason", cp.getValidationReason() != null
                            ? cp.getValidationReason()
                            : "Credential is invalid; update and re-validate");
                } else if (!"VALID".equals(status)) {
                    row.put("reason", null);
                } else {
                    row.put("reason", null);
                }
                // Include validation metadata for consumer transparency
                if (cp.getLastValidatedAt() != null) {
                    row.put("lastValidatedAt", cp.getLastValidatedAt().toString());
                }
                if (cp.getValidationCategory() != null) {
                    row.put("validationCategory", cp.getValidationCategory());
                }
            }
        }
        return row;
    }

    private String resolveConnectionKind(SystemOfRecord sor) {
        Map<String, Object> metadata = sor.getMetadata();
        if (metadata != null) {
            Object registryType = metadata.get("registry_type");
            if (registryType instanceof String registryTypeStr
                    && "TARGET".equalsIgnoreCase(registryTypeStr)) {
                return "target";
            }
        }
        return "source";
    }
}
