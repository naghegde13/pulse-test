package com.pulse.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.chat.plan.ContractImpactCode;
import com.pulse.chat.plan.ContractImpactDerivation;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.preflight.DeploymentPreflightService;
import com.pulse.deploy.preflight.PreflightCheckCode;
import com.pulse.deploy.preflight.PreflightCheckResult;
import com.pulse.deploy.projection.model.RuntimeProjection;
import com.pulse.deploy.projection.service.RuntimeProjectionService;
import com.pulse.deploy.projection.service.RuntimeProjectionService.ProjectionDriftResult;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.git.workspace.DeveloperWorkspaceService;
import com.pulse.git.workspace.WorkspaceDtos;
import com.pulse.storage.contract.service.StorageAuthorityFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Proof matrix for ARCH-018 dependency-backed read-only chat tools.
 * Verifies each tool dispatches to the canonical upstream service and
 * surfaces a stable, JSON-serializable payload to the LLM.
 */
@ExtendWith(MockitoExtension.class)
class ChatReadToolHandlerTest {

    @Mock private StorageAuthorityFacade storageAuthorityFacade;
    @Mock private RuntimeProjectionService runtimeProjectionService;
    @Mock private com.pulse.runtime.service.RuntimeAuthorityService runtimeAuthorityService;
    @Mock private DeploymentPreflightService deploymentPreflightService;
    @Mock private PackageRepository packageRepository;
    @Mock private DeveloperWorkspaceService developerWorkspaceService;
    @Mock private ContractImpactDerivation contractImpactDerivation;

    private ObjectMapper objectMapper;
    private ChatReadToolHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new ChatReadToolHandler(
                storageAuthorityFacade,
                runtimeProjectionService,
                runtimeAuthorityService,
                deploymentPreflightService,
                packageRepository,
                developerWorkspaceService,
                contractImpactDerivation,
                objectMapper);
    }

    // -----------------------------------------------------------------------
    // preview_dataset_landing
    // -----------------------------------------------------------------------

    @Test
    void previewDatasetLanding_delegatesToFacade_andReturnsJson() {
        when(storageAuthorityFacade.getDatasetLandingPreview("ds-1", "tenant-1", "dev"))
                .thenReturn(Map.of(
                        "datasetId", "ds-1",
                        "environment", "dev",
                        "status", "ok",
                        "relativeLandingPath", "domain/sor/ds/landing/"));

        String result = handler.previewDatasetLanding("tenant-1", Map.of(
                "dataset_id", "ds-1",
                "environment", "dev"));

        assertTrue(result.contains("\"datasetId\""));
        assertTrue(result.contains("\"ds-1\""));
        assertTrue(result.contains("\"status\""));
        assertTrue(result.contains("\"ok\""));
    }

    @Test
    void previewDatasetLanding_defaultsEnvironmentToDev() {
        when(storageAuthorityFacade.getDatasetLandingPreview("ds-1", "tenant-1", "dev"))
                .thenReturn(Map.of("datasetId", "ds-1", "status", "ok"));
        String result = handler.previewDatasetLanding("tenant-1", Map.of("dataset_id", "ds-1"));
        assertTrue(result.contains("\"ds-1\""));
    }

    @Test
    void previewDatasetLanding_missingDatasetId_returnsError() {
        String result = handler.previewDatasetLanding("tenant-1", Map.of());
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("dataset_id"));
    }

    // -----------------------------------------------------------------------
    // preview_table_contract
    // -----------------------------------------------------------------------

    @Test
    void previewTableContract_delegatesToFacade() {
        when(storageAuthorityFacade.getTableContractPreview("inst-1", "ver-1", "tenant-1", "uat"))
                .thenReturn(Map.of(
                        "instanceId", "inst-1",
                        "versionId", "ver-1",
                        "environment", "uat",
                        "status", "ok",
                        "contractCount", 1));

        String result = handler.previewTableContract("tenant-1", Map.of(
                "instance_id", "inst-1",
                "version_id", "ver-1",
                "environment", "uat"));

        assertTrue(result.contains("\"instanceId\""));
        assertTrue(result.contains("\"inst-1\""));
        assertTrue(result.contains("\"contractCount\""));
    }

    @Test
    void previewTableContract_missingInstanceOrVersion_returnsError() {
        assertTrue(handler.previewTableContract("t1", Map.of("version_id", "v1")).startsWith("Error"));
        assertTrue(handler.previewTableContract("t1", Map.of("instance_id", "i1")).startsWith("Error"));
    }

    // -----------------------------------------------------------------------
    // preview_runtime_projection
    // -----------------------------------------------------------------------

    @Test
    void previewRuntimeProjection_noProjection_surfacesNoProjectionStatus() {
        when(runtimeProjectionService.getActiveProjection("pkg-1", "tgt-1", "dev"))
                .thenReturn(Optional.empty());

        String result = handler.previewRuntimeProjection("tenant-1", Map.of(
                "package_id", "pkg-1",
                "target_id", "tgt-1"));

        assertTrue(result.contains("\"no_projection\""), "no projection should surface explicit status");
        assertTrue(result.contains("\"pkg-1\""));
    }

    @Test
    void previewRuntimeProjection_withProjection_includesDriftCheck() {
        RuntimeProjection projection = new RuntimeProjection();
        projection.setId("proj-1");
        projection.setPackageId("pkg-1");
        projection.setTargetId("tgt-1");
        projection.setEnvironment("dev");
        projection.setProjectionHash("abc123");
        projection.setStatus("active");
        projection.setRuntimePersona("GCP_PULSE");
        when(runtimeProjectionService.getActiveProjection("pkg-1", "tgt-1", "dev"))
                .thenReturn(Optional.of(projection));
        when(runtimeProjectionService.checkDrift("proj-1"))
                .thenReturn(new ProjectionDriftResult(false, "abc123", "abc123"));

        String result = handler.previewRuntimeProjection("tenant-1", Map.of(
                "package_id", "pkg-1",
                "target_id", "tgt-1"));

        assertTrue(result.contains("\"projectionId\""));
        assertTrue(result.contains("\"drift\""));
        assertTrue(result.contains("\"drifted\" : false"));
    }

    // -----------------------------------------------------------------------
    // check_table_contract_readiness
    // -----------------------------------------------------------------------

    @Test
    void checkTableContractReadiness_passThrough() {
        when(storageAuthorityFacade.getContractReadiness("ver-1"))
                .thenReturn(Map.of(
                        "versionId", "ver-1",
                        "ready", true,
                        "activeContractCount", 3,
                        "totalContracts", 3,
                        "blockers", List.of()));

        String result = handler.checkTableContractReadiness("tenant-1", Map.of("version_id", "ver-1"));
        assertTrue(result.contains("\"ready\" : true"));
        assertTrue(result.contains("\"activeContractCount\""));
    }

    // -----------------------------------------------------------------------
    // get_package_contract
    // -----------------------------------------------------------------------

    @Test
    void getPackageContract_returnsManifestFromMetadata() {
        Package pkg = new Package();
        pkg.setId("pkg-1");
        pkg.setPipelineId("pipe-1");
        pkg.setVersionId("ver-1");
        pkg.setMetadata(Map.of(
                "packageManifest", Map.of(
                        "schemaVersion", "deployment-package-manifest.v1",
                        "tableContractRefs", List.of())));
        when(packageRepository.findById("pkg-1")).thenReturn(Optional.of(pkg));

        String result = handler.getPackageContract("tenant-1", Map.of("package_id", "pkg-1"));
        assertTrue(result.contains("\"manifest\""));
        assertTrue(result.contains("\"deployment-package-manifest.v1\""));
        assertTrue(result.contains("\"status\" : \"ok\""));
    }

    @Test
    void getPackageContract_packageMissingManifest_surfacesNoManifestStatus() {
        Package pkg = new Package();
        pkg.setId("pkg-1");
        pkg.setMetadata(Map.of("artifactHash", "abc"));
        when(packageRepository.findById("pkg-1")).thenReturn(Optional.of(pkg));

        String result = handler.getPackageContract("tenant-1", Map.of("package_id", "pkg-1"));
        assertTrue(result.contains("\"no_manifest\""));
    }

    @Test
    void getPackageContract_unknownPackage_returnsError() {
        when(packageRepository.findById("pkg-x")).thenReturn(Optional.empty());
        String result = handler.getPackageContract("tenant-1", Map.of("package_id", "pkg-x"));
        assertTrue(result.startsWith("Error"));
    }

    // -----------------------------------------------------------------------
    // check_deploy_readiness
    // -----------------------------------------------------------------------

    @Test
    void checkDeployReadiness_returnsCanonicalPreflightJson() {
        PreflightCheckResult.CheckOutcome ok =
                PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.PACKAGE_COMPLETED);
        PreflightCheckResult result = PreflightCheckResult.of(
                "pkg-1", "tenant-1", "dev", "tgt-1",
                List.of(ok),
                Instant.parse("2026-05-14T00:00:00Z"));
        when(deploymentPreflightService.check(eq("pkg-1"), eq("tgt-1"), any(Instant.class)))
                .thenReturn(result);

        String body = handler.checkDeployReadiness("tenant-1", Map.of(
                "package_id", "pkg-1",
                "target_id", "tgt-1"));

        assertTrue(body.contains("\"schemaVersion\""));
        assertTrue(body.contains("deployment-preflight-result.v1"));
        assertTrue(body.contains("\"package_completed\"".toLowerCase())
                || body.contains("PACKAGE_COMPLETED"),
                "preflight check codes are part of the canonical payload");
    }

    // -----------------------------------------------------------------------
    // get_workspace_context
    // -----------------------------------------------------------------------

    @Test
    void getWorkspaceContext_noWorkspace_surfacesPipelineScopeHint() {
        WorkspaceDtos.WorkspaceContextDto ctx =
                new WorkspaceDtos.WorkspaceContextDto(null, null);
        when(developerWorkspaceService.getWorkspaceContext("ver-1")).thenReturn(ctx);

        String result = handler.getWorkspaceContext("tenant-1", Map.of("version_id", "ver-1"));
        assertTrue(result.contains("\"workspaceStatus\" : \"none\""));
        assertTrue(result.contains("\"PIPELINE\""));
    }

    @Test
    void getWorkspaceContext_withWorkspace_surfacesWorkspaceScopeHint() {
        WorkspaceDtos.WorkspaceStatusDto ws = new WorkspaceDtos.WorkspaceStatusDto(
                "ws-1", "tenant-1", "pipe-1", "ver-1", "repo-1", "user-1",
                "pulse/loan/r1-loan", "main", "abc", "/tmp/ws",
                false, "ACTIVE", "clean", "pushed", "open",
                "head-sha", "tree-sha", 0,
                null, null, null, null, null,
                0, null, null, Map.of());
        WorkspaceDtos.WorkspaceContextDto ctx =
                new WorkspaceDtos.WorkspaceContextDto(null, ws);
        when(developerWorkspaceService.getWorkspaceContext("ver-1")).thenReturn(ctx);

        String result = handler.getWorkspaceContext("tenant-1", Map.of("version_id", "ver-1"));
        assertTrue(result.contains("\"WORKSPACE\""));
        assertTrue(result.contains("\"ws-1\""));
        assertTrue(result.contains("\"workingTreeStatus\" : \"clean\""));
    }

    // -----------------------------------------------------------------------
    // derive_contract_impact
    // -----------------------------------------------------------------------

    @Test
    void deriveContractImpact_passThroughEnumWithMessage() {
        when(contractImpactDerivation.derive("ver-1", "pkg-1", "tgt-1", "dev"))
                .thenReturn(ContractImpactCode.TABLE_CONTRACT_STALE);

        String result = handler.deriveContractImpact("tenant-1", Map.of(
                "version_id", "ver-1",
                "package_id", "pkg-1",
                "target_id", "tgt-1"));

        assertTrue(result.contains("\"TABLE_CONTRACT_STALE\""));
        assertTrue(result.contains("\"message\""));
    }

    @Test
    void deriveContractImpact_noneCode_surfacesAllClear() {
        when(contractImpactDerivation.derive(anyString(), any(), any(), anyString()))
                .thenReturn(ContractImpactCode.NONE);
        // Use lenient stubbing to avoid strict-stubbing complaints if helpers infer different args.
        lenient().when(contractImpactDerivation.derive("ver-1", null, null, "dev"))
                .thenReturn(ContractImpactCode.NONE);

        String result = handler.deriveContractImpact("tenant-1", Map.of("version_id", "ver-1"));
        assertTrue(result.contains("\"NONE\""));
    }
}
