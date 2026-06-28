package com.pulse.deploy.projection;

import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.projection.model.RuntimeProjection;
import com.pulse.deploy.projection.model.RuntimeProjectionDdlStatement;
import com.pulse.deploy.projection.repository.RuntimeProjectionDdlStatementRepository;
import com.pulse.deploy.projection.repository.RuntimeProjectionRepository;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.projection.service.RuntimeProjectionService;
import com.pulse.deploy.projection.service.RuntimeProjectionService.ProjectionDriftResult;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.broker.mirror.RemoteTargetRuntimeMirror;
import com.pulse.broker.mirror.RemoteTargetRuntimeMirrorRepository;
import com.pulse.runtime.TestRuntimeAuthorityFactory;
import com.pulse.runtime.model.RuntimeBinding;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.runtime.service.RuntimeBindingAuthorityFacade;
import com.pulse.runtime.service.RuntimeBindingAuthorityFacade.ResolutionResult;
import com.pulse.storage.contract.model.TableContract;
import com.pulse.storage.contract.service.DdlPlanService;
import com.pulse.storage.contract.service.TableContractService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuntimeProjectionServiceTest {

    @Mock
    private RuntimeProjectionRepository projectionRepository;

    @Mock
    private RuntimeProjectionDdlStatementRepository ddlStatementRepository;

    @Mock
    private PackageRepository packageRepository;

    @Mock
    private DeploymentTargetRepository targetRepository;

    @Mock
    private RemoteTargetRuntimeMirrorRepository runtimeMirrorRepository;

    @Mock
    private RuntimeAuthorityService runtimeAuthorityService;

    @Mock
    private RuntimeBindingAuthorityFacade bindingAuthorityFacade;

    @Mock
    private TableContractService tableContractService;

    @Mock
    private DdlPlanService ddlPlanService;

    @InjectMocks
    private RuntimeProjectionService runtimeProjectionService;

    // ------------------------------------------------------------------ helpers

    private void mockGcpAuthority() {
        RuntimeAuthorityService gcpService = TestRuntimeAuthorityFactory.gcpPulse();
        when(runtimeAuthorityService.getAuthority()).thenReturn(gcpService.getAuthority());
    }

    // PKT-FINAL-5 / BUG-39: bindings are deployment-global; tenantId arg
    // preserved on the helper for call-site compatibility but unused on the entity.
    private RuntimeBinding buildBinding(String tenantId, String environment) {
        RuntimeBinding binding = new RuntimeBinding();
        binding.setId("binding-001");
        binding.setEnvironment(environment);
        binding.setBindingKind("GCP");
        binding.setSettingsRole("PRIMARY");
        binding.setRecordState("ACTIVE");
        binding.setStorageRootFiles("gs://tenant-files/");
        binding.setStorageRootLake("gs://tenant-lake/");
        binding.setStorageRootOps("gs://tenant-ops/");
        return binding;
    }

    private DeploymentTarget buildTarget(String tenantId, String environment) {
        DeploymentTarget target = new DeploymentTarget();
        target.setId("target-001");
        target.setTenantId(tenantId);
        target.setEnvironment(environment);
        target.setName("GCP Dev Target");
        target.setTargetType("GCP_COMPOSER_DATAPROC");
        target.setEnabled(true);
        return target;
    }

    private RuntimeProjectionDdlStatement buildDdlStatement(String statementId, String sha256) {
        RuntimeProjectionDdlStatement stmt = new RuntimeProjectionDdlStatement();
        stmt.setStatementId(statementId);
        stmt.setPhase(1);
        stmt.setExecutor("BIGQUERY_SQL");
        stmt.setDialect("BIGQUERY");
        stmt.setBody("CREATE SCHEMA IF NOT EXISTS finance_bronze;");
        stmt.setSha256(sha256);
        stmt.setIdempotencyMode("create_if_not_exists");
        return stmt;
    }

    private Package packageWithBrokerInvocation() {
        Package pkg = new Package();
        pkg.setId("pkg-broker-001");
        pkg.setTenantId("tenant-001");
        pkg.setMetadata(Map.of(
                "packageManifest", Map.of(
                        "entrypointCatalog", Map.of(
                                "brokerInvocations", List.of(Map.of(
                                        "instanceId", "inst-001",
                                        "federatedTenantKey", "peer-a",
                                        "remoteTargetRef", "target-a",
                                        "environment", "dev",
                                        "airflowConnectionId", "dpc_peer_airflow"))))));
        return pkg;
    }

    private Package packageWithOptionalCallbackPolicy() {
        Package pkg = new Package();
        pkg.setId("pkg-callback-001");
        pkg.setTenantId("tenant-001");
        pkg.setMetadata(Map.of(
                "packageManifest", Map.of(
                        "capabilityProfile", Map.of(
                                "controlPlaneDependency", "NONE",
                                "airflowCallbackPolicy", "OPTIONAL"),
                        "entrypointCatalog", Map.of(
                                "brokerInvocations", List.of()))));
        return pkg;
    }

    private Package packageWithTimeStateBinding() {
        Package pkg = new Package();
        pkg.setId("pkg-time-state-001");
        pkg.setTenantId("tenant-001");
        pkg.setMetadata(Map.of(
                "packageManifest", Map.of(
                        "entrypointCatalog", Map.of(
                                "timeStateBindings", List.of(Map.of(
                                        "instanceId", "inst-adv-001",
                                        "stateBindingRef", "time_state:dataset:loan-master",
                                        "variableKey", "pulse.time_state.tenant_001.loan_master",
                                        "calendarBindingRef", "calendar:servicing",
                                        "calendarBundleUri", "runtime/calendar/loan-master.json",
                                        "calendarBundleHash", "sha256:test-bundle",
                                        "evidencePrefix", "runtime-evidence/time-advances",
                                        "initializationPolicy", "require_existing",
                                        "concurrencyPolicy", "serialized_airflow"))))));
        return pkg;
    }

    // ------------------------------------------------------------------ create new

    @Test
    @DisplayName("createOrRefresh derives contracts and DDL from product state")
    void createOrRefresh_derivesFromProductState() {
        mockGcpAuthority();

        Package pkg = new Package();
        pkg.setId("pkg-001");
        pkg.setTenantId("tenant-001");
        pkg.setVersionId("ver-001");
        when(packageRepository.findById("pkg-001")).thenReturn(Optional.of(pkg));

        // Mock product-state contract derivation
        TableContract tc = new TableContract();
        tc.setId("tc-001");
        tc.setContractVersion(1);
        tc.setCatalogKind("HIVE");
        tc.setSchemaName("finance_bronze");
        tc.setCatalogTableName("salesforce_accounts");
        tc.setRelativeStoragePath("finance/bronze/salesforce/salesforce_accounts/");
        when(tableContractService.findActiveContracts("ver-001")).thenReturn(List.of(tc));

        // Mock DDL plan derivation
        DdlPlanService.DdlStatement ddlStmt = new DdlPlanService.DdlStatement(
                "ddl_1", "schema", "HIVE_JDBC", "HIVE",
                "CREATE DATABASE IF NOT EXISTS finance_bronze;",
                "abc123hash", "tc-001", "create_if_not_exists");
        when(ddlPlanService.generateDdlPlan(eq(List.of(tc)), eq("GCP_PULSE"), any()))
                .thenReturn(List.of(ddlStmt));

        when(projectionRepository.findByPackageIdAndTargetIdAndEnvironmentAndStatus(
                "pkg-001", "target-001", "dev", "active"))
                .thenReturn(Optional.empty());
        when(projectionRepository.save(any(RuntimeProjection.class)))
                .thenAnswer(inv -> {
                    RuntimeProjection p = inv.getArgument(0);
                    p.setId("proj-new-001");
                    return p;
                });
        when(ddlStatementRepository.save(any(RuntimeProjectionDdlStatement.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        RuntimeBinding binding = buildBinding("tenant-001", "dev");
        when(bindingAuthorityFacade.resolvePrimaryBinding("dev"))
                .thenReturn(new ResolutionResult.Resolved<>(binding));
        DeploymentTarget target = buildTarget("tenant-001", "dev");
        when(bindingAuthorityFacade.resolvePrimaryDeploymentTarget("tenant-001", "dev"))
                .thenReturn(new ResolutionResult.Resolved<>(target));

        RuntimeProjection result = runtimeProjectionService.createOrRefresh(
                "pkg-001", "target-001", "dev");

        assertNotNull(result);
        assertEquals("pkg-001", result.getPackageId());
        assertEquals("target-001", result.getTargetId());
        assertEquals("dev", result.getEnvironment());
        assertEquals("GCP_PULSE", result.getRuntimePersona());
        assertEquals("active", result.getStatus());
        assertNotNull(result.getProjectionHash());
        assertFalse(result.getProjectionHash().isBlank());
        assertTrue(result.getReadinessBlockers().isEmpty());

        // Verify DDL was derived from product state, not caller-supplied
        verify(tableContractService).findActiveContracts("ver-001");
        verify(ddlPlanService).generateDdlPlan(eq(List.of(tc)), eq("GCP_PULSE"), any());
        verify(ddlStatementRepository).save(any(RuntimeProjectionDdlStatement.class));
    }

    // ------------------------------------------------------------------ supersede existing

    @Test
    @DisplayName("createOrRefresh supersedes existing active projection")
    void createOrRefresh_supersedesExistingActive() {
        mockGcpAuthority();

        RuntimeProjection existing = new RuntimeProjection();
        existing.setId("proj-old-001");
        existing.setPackageId("pkg-001");
        existing.setTargetId("target-001");
        existing.setEnvironment("dev");
        existing.setStatus("active");

        Package pkg = new Package();
        pkg.setId("pkg-001");
        pkg.setTenantId("tenant-001");
        pkg.setVersionId("ver-001");
        when(packageRepository.findById("pkg-001")).thenReturn(Optional.of(pkg));
        when(tableContractService.findActiveContracts("ver-001")).thenReturn(List.of());
        when(projectionRepository.findByPackageIdAndTargetIdAndEnvironmentAndStatus(
                "pkg-001", "target-001", "dev", "active"))
                .thenReturn(Optional.of(existing));
        when(projectionRepository.save(any(RuntimeProjection.class)))
                .thenAnswer(inv -> {
                    RuntimeProjection p = inv.getArgument(0);
                    if (p.getId() == null) p.setId("proj-new-002");
                    return p;
                });

        RuntimeBinding binding = buildBinding("tenant-001", "dev");
        when(bindingAuthorityFacade.resolvePrimaryBinding("dev"))
                .thenReturn(new ResolutionResult.Resolved<>(binding));
        DeploymentTarget target = buildTarget("tenant-001", "dev");
        when(bindingAuthorityFacade.resolvePrimaryDeploymentTarget("tenant-001", "dev"))
                .thenReturn(new ResolutionResult.Resolved<>(target));

        runtimeProjectionService.createOrRefresh("pkg-001", "target-001", "dev");

        assertEquals("superseded", existing.getStatus());
        verify(projectionRepository, atLeast(2)).save(any(RuntimeProjection.class));
    }

    // ------------------------------------------------------------------ getActive delegation

    @Test
    @DisplayName("getActiveProjection delegates to repository")
    void getActiveProjection_delegatesToRepository() {
        RuntimeProjection projection = new RuntimeProjection();
        projection.setId("proj-100");
        projection.setPackageId("pkg-100");
        projection.setTargetId("target-100");
        projection.setEnvironment("dev");
        projection.setStatus("active");

        when(projectionRepository.findByPackageIdAndTargetIdAndEnvironmentAndStatus(
                "pkg-100", "target-100", "dev", "active"))
                .thenReturn(Optional.of(projection));

        Optional<RuntimeProjection> result = runtimeProjectionService.getActiveProjection(
                "pkg-100", "target-100", "dev");

        assertTrue(result.isPresent());
        assertEquals("proj-100", result.get().getId());
        verify(projectionRepository).findByPackageIdAndTargetIdAndEnvironmentAndStatus(
                "pkg-100", "target-100", "dev", "active");
    }

    // ------------------------------------------------------------------ drift detection

    @Test
    @DisplayName("checkDrift detects hash mismatch")
    void checkDrift_detectsHashMismatch() {
        RuntimeProjection projection = new RuntimeProjection();
        projection.setId("proj-drift-001");
        projection.setPackageId("pkg-001");
        projection.setTargetId("target-001");
        projection.setEnvironment("dev");
        projection.setRuntimePersona("GCP_PULSE");
        projection.setProjectionHash("original_hash_that_wont_match");
        projection.setStatus("active");

        when(projectionRepository.findById("proj-drift-001"))
                .thenReturn(Optional.of(projection));
        // checkDrift now derives contract versions from product state
        when(packageRepository.findById("pkg-001")).thenReturn(Optional.empty());

        // Return DDL statements with different hashes that won't match stored hash
        RuntimeProjectionDdlStatement stmt = new RuntimeProjectionDdlStatement();
        stmt.setStatementId("ddl_1");
        stmt.setSha256("different_sha256_value");
        stmt.setPhase(1);

        when(ddlStatementRepository.findByProjectionIdOrderByPhaseAsc("proj-drift-001"))
                .thenReturn(List.of(stmt));

        ProjectionDriftResult result = runtimeProjectionService.checkDrift("proj-drift-001");

        assertTrue(result.drifted());
        assertEquals("original_hash_that_wont_match", result.storedHash());
        assertNotEquals(result.storedHash(), result.currentHash());
    }

    @Test
    @DisplayName("freshly created projection with contracts is NOT immediately drifted")
    void checkDrift_freshProjectionWithContracts_notDrifted() {
        mockGcpAuthority();

        // Build a package with a versionId so contracts are derived
        Package pkg = new Package();
        pkg.setId("pkg-fresh");
        pkg.setTenantId("tenant-001");
        pkg.setVersionId("ver-fresh");
        when(packageRepository.findById("pkg-fresh")).thenReturn(Optional.of(pkg));

        // Product-state table contract
        TableContract tc = new TableContract();
        tc.setId("tc-fresh-001");
        tc.setContractVersion(1);
        tc.setCatalogKind("HIVE");
        tc.setSchemaName("finance_bronze");
        tc.setCatalogTableName("salesforce_accounts");
        tc.setRelativeStoragePath("finance/bronze/salesforce/salesforce_accounts/");
        when(tableContractService.findActiveContracts("ver-fresh")).thenReturn(List.of(tc));

        // DDL derived from product state
        DdlPlanService.DdlStatement ddlPlanStmt = new DdlPlanService.DdlStatement(
                "ddl_f1", "schema", "HIVE_JDBC", "HIVE",
                "CREATE DATABASE IF NOT EXISTS finance_bronze;",
                "freshddlhash001", "tc-fresh-001", "create_if_not_exists");
        when(ddlPlanService.generateDdlPlan(eq(List.of(tc)), eq("GCP_PULSE"), any()))
                .thenReturn(List.of(ddlPlanStmt));

        when(projectionRepository.findByPackageIdAndTargetIdAndEnvironmentAndStatus(
                "pkg-fresh", "target-fresh", "dev", "active"))
                .thenReturn(Optional.empty());

        // Capture the saved projection so we can feed it back to checkDrift
        final RuntimeProjection[] saved = new RuntimeProjection[1];
        when(projectionRepository.save(any(RuntimeProjection.class))).thenAnswer(inv -> {
            RuntimeProjection p = inv.getArgument(0);
            if (p.getStatus().equals("active")) {
                p.setId("proj-fresh-001");
                saved[0] = p;
            }
            return p;
        });
        when(ddlStatementRepository.save(any(RuntimeProjectionDdlStatement.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        RuntimeBinding binding = buildBinding("tenant-001", "dev");
        when(bindingAuthorityFacade.resolvePrimaryBinding("dev"))
                .thenReturn(new ResolutionResult.Resolved<>(binding));
        DeploymentTarget target = buildTarget("tenant-001", "dev");
        when(bindingAuthorityFacade.resolvePrimaryDeploymentTarget("tenant-001", "dev"))
                .thenReturn(new ResolutionResult.Resolved<>(target));

        // --- Step 1: create projection ---
        RuntimeProjection result = runtimeProjectionService.createOrRefresh(
                "pkg-fresh", "target-fresh", "dev");
        assertNotNull(result);
        assertNotNull(result.getProjectionHash());

        // --- Step 2: immediately check drift ---
        when(projectionRepository.findById("proj-fresh-001"))
                .thenReturn(Optional.of(saved[0]));

        // Return the same DDL statement that was persisted during creation
        RuntimeProjectionDdlStatement persistedStmt = new RuntimeProjectionDdlStatement();
        persistedStmt.setSha256("freshddlhash001");
        persistedStmt.setPhase(1);
        when(ddlStatementRepository.findByProjectionIdOrderByPhaseAsc("proj-fresh-001"))
                .thenReturn(List.of(persistedStmt));

        ProjectionDriftResult drift = runtimeProjectionService.checkDrift("proj-fresh-001");

        assertFalse(drift.drifted(),
                "A freshly created projection with contracts must NOT be immediately drifted");
        assertEquals(drift.storedHash(), drift.currentHash());
    }

    // ------------------------------------------------------------------ readiness blockers

    @Test
    @DisplayName("createOrRefresh records readiness blockers when binding unresolved")
    void createOrRefresh_recordsBlockers_whenBindingUnresolved() {
        mockGcpAuthority();

        Package pkg = new Package();
        pkg.setId("pkg-002");
        pkg.setTenantId("tenant-001");
        when(packageRepository.findById("pkg-002")).thenReturn(Optional.of(pkg));
        when(projectionRepository.findByPackageIdAndTargetIdAndEnvironmentAndStatus(
                any(), any(), any(), eq("active")))
                .thenReturn(Optional.empty());
        when(projectionRepository.save(any(RuntimeProjection.class)))
                .thenAnswer(inv -> {
                    RuntimeProjection p = inv.getArgument(0);
                    p.setId("proj-blocked-001");
                    return p;
                });

        when(bindingAuthorityFacade.resolvePrimaryBinding("uat"))
                .thenReturn(new ResolutionResult.Unresolved<>(
                        "binding_missing", "No active PRIMARY binding for uat"));
        when(bindingAuthorityFacade.resolvePrimaryDeploymentTarget("tenant-001", "uat"))
                .thenReturn(new ResolutionResult.Unresolved<>(
                        "target_binding_missing", "No deployment target for uat"));

        RuntimeProjection result = runtimeProjectionService.createOrRefresh(
                "pkg-002", "target-002", "uat");

        assertNotNull(result.getReadinessBlockers());
        assertEquals(2, result.getReadinessBlockers().size());
    }

    @Test
    @DisplayName("createOrRefresh resolves broker edge bindings from runtime mirror")
    void createOrRefresh_resolvesBrokerEdgeBindings() {
        mockGcpAuthority();
        Package pkg = packageWithBrokerInvocation();
        when(packageRepository.findById("pkg-broker-001")).thenReturn(Optional.of(pkg));
        when(projectionRepository.findByPackageIdAndTargetIdAndEnvironmentAndStatus(
                "pkg-broker-001", "target-001", "dev", "active"))
                .thenReturn(Optional.empty());
        when(projectionRepository.save(any(RuntimeProjection.class))).thenAnswer(inv -> {
            RuntimeProjection p = inv.getArgument(0);
            p.setId("proj-broker-001");
            return p;
        });
        RuntimeBinding binding = buildBinding("tenant-001", "dev");
        when(bindingAuthorityFacade.resolvePrimaryBinding("dev"))
                .thenReturn(new ResolutionResult.Resolved<>(binding));
        DeploymentTarget target = buildTarget("tenant-001", "dev");
        when(bindingAuthorityFacade.resolvePrimaryDeploymentTarget("tenant-001", "dev"))
                .thenReturn(new ResolutionResult.Resolved<>(target));
        RemoteTargetRuntimeMirror mirror = new RemoteTargetRuntimeMirror();
        mirror.setId("mirror-001");
        mirror.setTrustBindingId("trust-001");
        mirror.setFederatedTenantKey("peer-a");
        mirror.setRemoteTargetRef("target-a");
        mirror.setEnvironment("dev");
        mirror.setPeerLogicalDagId("peer_dag");
        when(runtimeMirrorRepository.findFirstByFederatedTenantKeyAndRemoteTargetRefAndEnvironment(
                "peer-a", "target-a", "dev"))
                .thenReturn(Optional.of(mirror));

        RuntimeProjection result = runtimeProjectionService.createOrRefresh(
                "pkg-broker-001", "target-001", "dev");

        assertTrue(result.getReadinessBlockers().isEmpty());
        assertNotNull(result.getResolvedEntrypoints());
        Object bindings = result.getResolvedEntrypoints().get("brokerEdgeBindings");
        assertInstanceOf(List.class, bindings);
        assertEquals(1, ((List<?>) bindings).size());
        Map<?, ?> brokerBinding = (Map<?, ?>) ((List<?>) bindings).get(0);
        assertEquals("dpc_peer_airflow", brokerBinding.get("airflowConnectionId"));
        assertEquals("peer_dag", brokerBinding.get("remoteDagId"));
    }

    @Test
    @DisplayName("createOrRefresh records broker blockers when mirror is missing")
    void createOrRefresh_recordsBrokerBlockersWhenMirrorMissing() {
        mockGcpAuthority();
        Package pkg = packageWithBrokerInvocation();
        when(packageRepository.findById("pkg-broker-001")).thenReturn(Optional.of(pkg));
        when(projectionRepository.findByPackageIdAndTargetIdAndEnvironmentAndStatus(
                any(), any(), any(), eq("active")))
                .thenReturn(Optional.empty());
        when(projectionRepository.save(any(RuntimeProjection.class))).thenAnswer(inv -> {
            RuntimeProjection p = inv.getArgument(0);
            p.setId("proj-broker-blocked");
            return p;
        });
        RuntimeBinding binding = buildBinding("tenant-001", "dev");
        when(bindingAuthorityFacade.resolvePrimaryBinding("dev"))
                .thenReturn(new ResolutionResult.Resolved<>(binding));
        DeploymentTarget target = buildTarget("tenant-001", "dev");
        when(bindingAuthorityFacade.resolvePrimaryDeploymentTarget("tenant-001", "dev"))
                .thenReturn(new ResolutionResult.Resolved<>(target));
        when(runtimeMirrorRepository.findFirstByFederatedTenantKeyAndRemoteTargetRefAndEnvironment(
                "peer-a", "target-a", "dev"))
                .thenReturn(Optional.empty());

        RuntimeProjection result = runtimeProjectionService.createOrRefresh(
                "pkg-broker-001", "target-001", "dev");

        assertEquals(2, result.getReadinessBlockers().size());
        assertTrue(result.getReadinessBlockers().stream()
                .anyMatch(b -> "BROKER_TARGET_RUNTIME_MIRROR_MISSING".equals(b.get("code"))));
        assertTrue(result.getReadinessBlockers().stream()
                .anyMatch(b -> "BROKER_EDGE_BINDINGS_INCOMPLETE".equals(b.get("code"))));
    }

    @Test
    @DisplayName("createOrRefresh carries time-state bindings into resolved entrypoints")
    void createOrRefresh_resolvesTimeStateBindings() {
        mockGcpAuthority();
        Package pkg = packageWithTimeStateBinding();
        when(packageRepository.findById("pkg-time-state-001")).thenReturn(Optional.of(pkg));
        when(projectionRepository.findByPackageIdAndTargetIdAndEnvironmentAndStatus(
                "pkg-time-state-001", "target-001", "dev", "active"))
                .thenReturn(Optional.empty());
        when(projectionRepository.save(any(RuntimeProjection.class))).thenAnswer(inv -> {
            RuntimeProjection p = inv.getArgument(0);
            p.setId("proj-time-state-001");
            return p;
        });
        RuntimeBinding binding = buildBinding("tenant-001", "dev");
        when(bindingAuthorityFacade.resolvePrimaryBinding("dev"))
                .thenReturn(new ResolutionResult.Resolved<>(binding));
        DeploymentTarget target = buildTarget("tenant-001", "dev");
        when(bindingAuthorityFacade.resolvePrimaryDeploymentTarget("tenant-001", "dev"))
                .thenReturn(new ResolutionResult.Resolved<>(target));

        RuntimeProjection result = runtimeProjectionService.createOrRefresh(
                "pkg-time-state-001", "target-001", "dev");

        assertTrue(result.getReadinessBlockers().isEmpty());
        Object bindings = result.getResolvedEntrypoints().get("timeStateBindings");
        assertInstanceOf(List.class, bindings);
        assertEquals(1, ((List<?>) bindings).size());
        Map<?, ?> bindingRow = (Map<?, ?>) ((List<?>) bindings).get(0);
        assertEquals("time_state:dataset:loan-master", bindingRow.get("stateBindingRef"));
        assertEquals("pulse.time_state.tenant_001.loan_master", bindingRow.get("variableKey"));
    }

    @Test
    @DisplayName("createOrRefresh projects explicit local dev callback configuration only for OPTIONAL policy")
    void createOrRefresh_projectsLocalDevAirflowCallbackSettings() {
        mockGcpAuthority();
        Package pkg = packageWithOptionalCallbackPolicy();
        DeploymentTarget target = buildTarget("tenant-001", "dev");
        target.setConfig(Map.of(
                "airflowCallbackUrl", "https://pulse-dev.example.com/runtime/airflow-callback",
                "airflowCallbackTimeoutSeconds", 15,
                "airflowCallbackEvents", List.of("failure")));

        when(packageRepository.findById("pkg-callback-001")).thenReturn(Optional.of(pkg));
        when(targetRepository.findById("target-001")).thenReturn(Optional.of(target));
        when(projectionRepository.findByPackageIdAndTargetIdAndEnvironmentAndStatus(
                "pkg-callback-001", "target-001", "dev", "active"))
                .thenReturn(Optional.empty());
        when(projectionRepository.save(any(RuntimeProjection.class))).thenAnswer(inv -> {
            RuntimeProjection p = inv.getArgument(0);
            p.setId("proj-callback-001");
            return p;
        });

        RuntimeBinding binding = buildBinding("tenant-001", "dev");
        when(bindingAuthorityFacade.resolvePrimaryBinding("dev"))
                .thenReturn(new ResolutionResult.Resolved<>(binding));
        when(bindingAuthorityFacade.resolvePrimaryDeploymentTarget("tenant-001", "dev"))
                .thenReturn(new ResolutionResult.Resolved<>(target));

        RuntimeProjection result = runtimeProjectionService.createOrRefresh(
                "pkg-callback-001", "target-001", "dev");

        assertTrue(result.getReadinessBlockers().isEmpty());
        @SuppressWarnings("unchecked")
        Map<String, Object> callbacks = (Map<String, Object>) result.getResolvedEntrypoints().get("airflowCallbacks");
        assertNotNull(callbacks);
        assertEquals("PULSE_AIRFLOW_CALLBACK_URL", callbacks.get("envVar"));
        assertEquals("https://pulse-dev.example.com/runtime/airflow-callback", callbacks.get("airflowCallbackUrl"));
        assertEquals(15, callbacks.get("airflowCallbackTimeoutSeconds"));
        assertEquals(List.of("failure"), callbacks.get("airflowCallbackEvents"));
    }

    @Test
    @DisplayName("createOrRefresh blocks OPTIONAL callback policy outside local dev")
    void createOrRefresh_blocksOptionalAirflowCallbackPolicyInPromotedEnv() {
        mockGcpAuthority();
        Package pkg = packageWithOptionalCallbackPolicy();

        when(packageRepository.findById("pkg-callback-001")).thenReturn(Optional.of(pkg));
        when(projectionRepository.findByPackageIdAndTargetIdAndEnvironmentAndStatus(
                "pkg-callback-001", "target-001", "prod", "active"))
                .thenReturn(Optional.empty());
        when(projectionRepository.save(any(RuntimeProjection.class))).thenAnswer(inv -> {
            RuntimeProjection p = inv.getArgument(0);
            p.setId("proj-callback-promoted");
            return p;
        });

        RuntimeBinding binding = buildBinding("tenant-001", "prod");
        when(bindingAuthorityFacade.resolvePrimaryBinding("prod"))
                .thenReturn(new ResolutionResult.Resolved<>(binding));
        DeploymentTarget target = buildTarget("tenant-001", "prod");
        when(bindingAuthorityFacade.resolvePrimaryDeploymentTarget("tenant-001", "prod"))
                .thenReturn(new ResolutionResult.Resolved<>(target));

        RuntimeProjection result = runtimeProjectionService.createOrRefresh(
                "pkg-callback-001", "target-001", "prod");

        assertTrue(result.getReadinessBlockers().stream()
                .anyMatch(b -> "AIRFLOW_CALLBACK_POLICY_PROMOTED_ENV".equals(b.get("code"))));
        assertNull(result.getResolvedEntrypoints().get("airflowCallbacks"));
    }

    @Test
    @DisplayName("createOrRefresh signature takes no caller-supplied contracts or DDL")
    void createOrRefresh_noCallerSuppliedContractsOrDdl() {
        // PKT-0023 evidence: the createOrRefresh method signature is
        // (packageId, targetId, environment) — no tableContracts or
        // ddlStatements parameters. Contracts and DDL are derived from
        // product state via TableContractService and DdlPlanService.
        // This test documents the API contract: the old 5-arg overload
        // accepting caller-supplied body no longer exists.
        var methods = java.util.Arrays.stream(RuntimeProjectionService.class.getDeclaredMethods())
                .filter(m -> "createOrRefresh".equals(m.getName()))
                .toList();
        assertEquals(1, methods.size(), "Only one createOrRefresh overload should exist");
        assertEquals(3, methods.get(0).getParameterCount(),
                "createOrRefresh should take exactly 3 params: packageId, targetId, environment");
    }

    @Test
    @DisplayName("createOrRefresh rejects localhost callback fallback")
    void createOrRefresh_rejectsLocalhostCallbackFallback() {
        mockGcpAuthority();
        Package pkg = packageWithOptionalCallbackPolicy();
        DeploymentTarget target = buildTarget("tenant-001", "dev");
        target.setConfig(Map.of("airflowCallbackUrl", "http://localhost:8080/api/v1/callbacks/airflow"));

        when(packageRepository.findById("pkg-callback-001")).thenReturn(Optional.of(pkg));
        when(targetRepository.findById("target-001")).thenReturn(Optional.of(target));
        when(projectionRepository.findByPackageIdAndTargetIdAndEnvironmentAndStatus(
                "pkg-callback-001", "target-001", "dev", "active"))
                .thenReturn(Optional.empty());
        when(projectionRepository.save(any(RuntimeProjection.class))).thenAnswer(inv -> {
            RuntimeProjection p = inv.getArgument(0);
            p.setId("proj-callback-localhost");
            return p;
        });

        RuntimeBinding binding = buildBinding("tenant-001", "dev");
        when(bindingAuthorityFacade.resolvePrimaryBinding("dev"))
                .thenReturn(new ResolutionResult.Resolved<>(binding));
        when(bindingAuthorityFacade.resolvePrimaryDeploymentTarget("tenant-001", "dev"))
                .thenReturn(new ResolutionResult.Resolved<>(target));

        RuntimeProjection result = runtimeProjectionService.createOrRefresh(
                "pkg-callback-001", "target-001", "dev");

        assertTrue(result.getReadinessBlockers().stream()
                .anyMatch(b -> "AIRFLOW_CALLBACK_LOCALHOST_FORBIDDEN".equals(b.get("code"))));
        assertNull(result.getResolvedEntrypoints().get("airflowCallbacks"));
    }
}
