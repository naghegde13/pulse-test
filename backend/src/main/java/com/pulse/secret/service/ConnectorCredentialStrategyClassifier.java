package com.pulse.secret.service;

import com.pulse.auth.model.TenantGcpCredential;
import com.pulse.auth.service.TenantGcpCredentialService;
import com.pulse.sor.model.ConnectorCredentialStrategy;
import com.pulse.sor.model.ConnectorDefinition;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.storage.model.StorageScaffoldStatus;
import com.pulse.storage.repository.StorageScaffoldStatusRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PKT-0018: Resolves credential strategy for a connector instance and
 * computes lifecycle-file readiness when the strategy is
 * {@link ConnectorCredentialStrategy#INHERIT_TENANT_GCP_SERVICE_ACCOUNT}.
 * <p>
 * Lifecycle-file connectors (S3-compatible Object Storage) inherit tenant GCP
 * SA credentials and depend on:
 * <ol>
 *   <li>Tenant GCP credential being active</li>
 *   <li>At least one storage scaffold being previewed/executed for the tenant</li>
 * </ol>
 * <p>
 * External connectors return {@code CONNECTOR_SPECIFIC} with no additional
 * readiness computation — they use the standard credential profile flow.
 * <p>
 * Readbacks are always secret-safe: project ID, service account email,
 * scaffold status — never private keys or credential JSON.
 */
@Service
public class ConnectorCredentialStrategyClassifier {

    private final ConnectorInstanceRepository ciRepo;
    private final ConnectorDefinitionRepository connDefRepo;
    private final TenantGcpCredentialService tenantGcpCredentialService;
    private final StorageScaffoldStatusRepository scaffoldRepo;

    public ConnectorCredentialStrategyClassifier(
            ConnectorInstanceRepository ciRepo,
            ConnectorDefinitionRepository connDefRepo,
            TenantGcpCredentialService tenantGcpCredentialService,
            StorageScaffoldStatusRepository scaffoldRepo) {
        this.ciRepo = ciRepo;
        this.connDefRepo = connDefRepo;
        this.tenantGcpCredentialService = tenantGcpCredentialService;
        this.scaffoldRepo = scaffoldRepo;
    }

    /**
     * Resolve the credential strategy for a connector definition.
     */
    public ConnectorCredentialStrategy resolveForDefinition(ConnectorDefinition def) {
        return def.getCredentialStrategy();
    }

    /**
     * Resolve the credential strategy for a connector instance by looking up
     * its parent connector definition.
     *
     * @return the strategy, or {@code CONNECTOR_SPECIFIC} if the definition is not found
     */
    public ConnectorCredentialStrategy resolveForInstance(String connectorInstanceId) {
        return ciRepo.findById(connectorInstanceId)
                .flatMap(ci -> connDefRepo.findById(ci.getConnectorDefinitionId()))
                .map(ConnectorDefinition::getCredentialStrategy)
                .orElse(ConnectorCredentialStrategy.CONNECTOR_SPECIFIC);
    }

    /**
     * Resolve the credential strategy for a connector instance entity.
     */
    public ConnectorCredentialStrategy resolveForInstance(ConnectorInstance ci) {
        return connDefRepo.findById(ci.getConnectorDefinitionId())
                .map(ConnectorDefinition::getCredentialStrategy)
                .orElse(ConnectorCredentialStrategy.CONNECTOR_SPECIFIC);
    }

    /**
     * Compute lifecycle-file readiness for a tenant. Returns a structured
     * readiness map with status, blockers, and redacted context.
     * <p>
     * Ready when:
     * <ul>
     *   <li>Tenant GCP credential exists and is active</li>
     *   <li>At least one storage scaffold status exists for the tenant</li>
     * </ul>
     */
    public Map<String, Object> computeLifecycleFileReadiness(String tenantId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("credentialStrategy", ConnectorCredentialStrategy.INHERIT_TENANT_GCP_SERVICE_ACCOUNT.name());
        result.put("tenantId", tenantId);

        boolean ready = true;
        List<String> blockers = new java.util.ArrayList<>();

        // Check 1: Tenant GCP credential
        Optional<TenantGcpCredential> credOpt = tenantGcpCredentialService.getCredentialEntity(tenantId);
        if (credOpt.isEmpty()) {
            ready = false;
            blockers.add("No tenant GCP credential configured. "
                    + "Submit via PUT /api/v1/tenants/{tenantId}/gcp-credentials.");
            result.put("gcpCredentialStatus", "missing");
        } else {
            TenantGcpCredential cred = credOpt.get();
            if (!"active".equalsIgnoreCase(cred.getStatus())) {
                ready = false;
                blockers.add("Tenant GCP credential exists but status is '"
                        + cred.getStatus() + "'; must be 'active'.");
                result.put("gcpCredentialStatus", cred.getStatus());
            } else {
                result.put("gcpCredentialStatus", "active");
                result.put("gcpProjectId", cred.getControlPlaneProjectId());
                result.put("serviceAccountEmail", cred.getServiceAccountEmail());
            }
            result.put("privateKeyRedacted", true);
        }

        // Check 2: Storage scaffold status
        List<StorageScaffoldStatus> scaffolds = scaffoldRepo.findByTenantId(tenantId);
        if (scaffolds.isEmpty()) {
            ready = false;
            blockers.add("No storage scaffold exists for this tenant. "
                    + "Preview storage scaffold via POST /api/v1/tenants/{tenantId}/storage-scaffold/preview.");
            result.put("storageScaffoldStatus", "missing");
        } else {
            boolean anyExecutedOrPreviewed = scaffolds.stream()
                    .anyMatch(s -> "executed".equals(s.getStatus()) || "previewed".equals(s.getStatus()));
            if (!anyExecutedOrPreviewed) {
                ready = false;
                blockers.add("Storage scaffold exists but none are in 'previewed' or 'executed' state.");
                result.put("storageScaffoldStatus", "not_ready");
            } else {
                result.put("storageScaffoldStatus", "ready");
                result.put("scaffoldCount", scaffolds.size());
            }
        }

        result.put("ready", ready);
        if (!blockers.isEmpty()) {
            result.put("blockers", blockers);
        }
        return result;
    }
}
