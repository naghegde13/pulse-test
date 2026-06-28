package com.pulse.tenant.controller;

import com.pulse.auth.filter.JwtPrincipal;
import com.pulse.auth.policy.PulseRole;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.tenant.model.TenantGcpRuntimeTopology;
import com.pulse.tenant.service.GcpIamManifestService;
import com.pulse.tenant.service.GcpRuntimeTopologyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * PKT-0025: Product API surface for tenant GCP runtime topology and
 * least-privilege IAM manifest.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET .../gcp-runtime-topology} — read topology readback</li>
 *   <li>{@code PUT .../gcp-runtime-topology} — upsert topology (TENANT_ADMIN or PLATFORM_ADMIN)</li>
 *   <li>{@code GET .../gcp-iam-manifest} — generated least-privilege IAM manifest</li>
 * </ul>
 *
 * <p>The PUT mutation is role-gated: same-tenant TENANT_ADMIN or PLATFORM_ADMIN
 * can mutate topology; same-tenant non-admin is denied; cross-tenant
 * non-PLATFORM_ADMIN is denied. Read endpoints are open to authorized
 * tenant members.
 *
 * <p>No live GCP execution is performed. IAM binding execution is
 * OPERATOR_BLOCKED.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
public class TenantGcpRuntimeTopologyController {

    private final GcpRuntimeTopologyService topologyService;
    private final GcpIamManifestService iamManifestService;

    public TenantGcpRuntimeTopologyController(GcpRuntimeTopologyService topologyService,
                                               GcpIamManifestService iamManifestService) {
        this.topologyService = topologyService;
        this.iamManifestService = iamManifestService;
    }

    @GetMapping("/gcp-runtime-topology")
    public ResponseEntity<Map<String, Object>> getTopology(@PathVariable String tenantId) {
        return topologyService.getTopology(tenantId)
                .map(t -> ResponseEntity.ok(topologyService.buildReadback(t)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No GCP runtime topology configured for tenant: " + tenantId));
    }

    @PutMapping("/gcp-runtime-topology")
    public ResponseEntity<Map<String, Object>> setTopology(
            @PathVariable String tenantId,
            @RequestBody TopologyRequest request) {
        // Role gate: same-tenant TENANT_ADMIN or PLATFORM_ADMIN required for mutation
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof JwtPrincipal principal) {
            if (!PulseRole.isPlatformAdmin(principal.role()) && !tenantId.equals(principal.tenantId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: tenant mismatch");
            }
            if (!PulseRole.isTenantAdminOrHigher(principal.role())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Topology mutation requires TENANT_ADMIN or PLATFORM_ADMIN role");
            }
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        try {
            TenantGcpRuntimeTopology incoming = request.toEntity();
            TenantGcpRuntimeTopology saved = topologyService.setTopology(tenantId, incoming);
            return ResponseEntity.ok(topologyService.buildReadback(saved));
        } catch (ResourceNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping("/gcp-iam-manifest")
    public ResponseEntity<Map<String, Object>> getIamManifest(@PathVariable String tenantId) {
        return ResponseEntity.ok(iamManifestService.generateManifest(tenantId));
    }

    /**
     * Request body for topology upsert. Maps flat JSON fields to entity.
     */
    public record TopologyRequest(
            // Composer
            String composerProjectId, String composerEnvironment, String composerRegion,
            String composerEnvironmentBucket, String composerDagPrefix,
            String composerPluginsPrefix, String composerDataPrefix, String composerLogPrefix,
            // Dataproc
            String dataprocProjectId, String dataprocRegion, String dataprocWorkloadSaEmail,
            String dataprocNetwork, String dataprocSubnet, String dataprocStagingBucket,
            // BigQuery
            String bqProjectId, String bqLocation, String bqDatasetBronze,
            String bqDatasetSilver, String bqDatasetGold,
            // BQ connection
            String bqConnectionId, String bqConnectionRegion, String bqConnectionSaEmail,
            // Iceberg
            String icebergStorageBucket,
            // Evidence
            String evidenceSinkBucket, String evidenceSinkDataset,
            // Secret Manager
            String secretManagerProjectId,
            // Logging
            String loggingProjectId, String loggingLogBucket,
            // Control plane
            String controlPlaneSaEmail
    ) {
        public TenantGcpRuntimeTopology toEntity() {
            TenantGcpRuntimeTopology t = new TenantGcpRuntimeTopology();
            t.setComposerProjectId(composerProjectId);
            t.setComposerEnvironment(composerEnvironment);
            t.setComposerRegion(composerRegion);
            t.setComposerEnvironmentBucket(composerEnvironmentBucket);
            t.setComposerDagPrefix(composerDagPrefix);
            t.setComposerPluginsPrefix(composerPluginsPrefix);
            t.setComposerDataPrefix(composerDataPrefix);
            t.setComposerLogPrefix(composerLogPrefix);
            t.setDataprocProjectId(dataprocProjectId);
            t.setDataprocRegion(dataprocRegion);
            t.setDataprocWorkloadSaEmail(dataprocWorkloadSaEmail);
            t.setDataprocNetwork(dataprocNetwork);
            t.setDataprocSubnet(dataprocSubnet);
            t.setDataprocStagingBucket(dataprocStagingBucket);
            t.setBqProjectId(bqProjectId);
            t.setBqLocation(bqLocation);
            t.setBqDatasetBronze(bqDatasetBronze);
            t.setBqDatasetSilver(bqDatasetSilver);
            t.setBqDatasetGold(bqDatasetGold);
            t.setBqConnectionId(bqConnectionId);
            t.setBqConnectionRegion(bqConnectionRegion);
            t.setBqConnectionSaEmail(bqConnectionSaEmail);
            t.setIcebergStorageBucket(icebergStorageBucket);
            t.setEvidenceSinkBucket(evidenceSinkBucket);
            t.setEvidenceSinkDataset(evidenceSinkDataset);
            t.setSecretManagerProjectId(secretManagerProjectId);
            t.setLoggingProjectId(loggingProjectId);
            t.setLoggingLogBucket(loggingLogBucket);
            t.setControlPlaneSaEmail(controlPlaneSaEmail);
            return t;
        }
    }
}
