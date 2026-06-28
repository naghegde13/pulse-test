package com.pulse.runtime;

import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.runtime.config.RuntimeAuthorityProperties;
import com.pulse.runtime.model.RuntimeBinding;
import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.repository.RuntimeBindingRepository;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.runtime.service.RuntimeAuthorityService.RuntimeAuthorityViolationException;
import com.pulse.runtime.service.RuntimeBindingAuthorityFacade;
import com.pulse.runtime.service.RuntimeBindingAuthorityFacade.ResolutionResult;
import com.pulse.runtime.service.RuntimeBindingService;
import com.pulse.runtime.service.RuntimeBindingValidationService;
import com.pulse.runtime.service.RuntimeBindingValidationService.ValidationResult;
import com.pulse.tenant.service.TenantRuntimeReadinessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * PKT-0014: Tenant Runtime and Deployment Target Readiness proof.
 *
 * <p>Proves:
 * <ul>
 *   <li>Runtime authority readback — active persona and legal matrix</li>
 *   <li>Runtime binding CRUD + validate with validationKind/status</li>
 *   <li>Authority facade — active PRIMARY vs DIAGNOSTIC exclusion</li>
 *   <li>Deployment target — persona legality</li>
 *   <li>Tenant readiness categories — runtimeBinding and deploymentTarget</li>
 *   <li>Negative: illegal target type rejection</li>
 *   <li>Negative: stub/static validation cannot mark live GCP ready</li>
 *   <li>Negative: missing bindings/targets block readiness</li>
 *   <li>Package deploy preflight is not required/executed by this packet</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TenantRuntimeDeploymentReadinessTest {

    private static final String TENANT = "acme-lending";

    // ================================================================
    // §1 — Runtime Authority Readback
    // ================================================================
    @Nested
    @DisplayName("§1 Runtime Authority Readback")
    class RuntimeAuthorityReadback {

        private RuntimeAuthorityService gcpService;

        @BeforeEach
        void setUp() {
            RuntimeAuthorityProperties props = new RuntimeAuthorityProperties();
            props.setActivePersona("GCP_PULSE");
            gcpService = new RuntimeAuthorityService(props);
            gcpService.initialize();
        }

        @Test
        @DisplayName("Active persona is GCP_PULSE with legal matrix version")
        void activePersonaAndMatrix() {
            var auth = gcpService.getAuthority();
            assertEquals(RuntimePersona.GCP_PULSE, auth.activePersona());
            assertNotNull(auth.legalRuntimeMatrixVersion(),
                    "Legal runtime matrix version must be present");
            assertFalse(auth.allowedTargetTypes().isEmpty(),
                    "Allowed target types must not be empty");
            assertTrue(auth.allowedTargetTypes().contains("GCP_COMPOSER_DATAPROC"),
                    "GCP persona must allow GCP_COMPOSER_DATAPROC");
        }

        @Test
        @DisplayName("Authority readback includes all categories")
        void authorityReadbackCategories() {
            var auth = gcpService.getAuthority();
            assertNotNull(auth.allowedStorageBackends());
            assertNotNull(auth.allowedOrchestrators());
            assertNotNull(auth.allowedComputeRuntimes());
            assertNotNull(auth.allowedStorageKinds());
            assertNotNull(auth.allowedCatalogs());
            assertNotNull(auth.allowedBrokerPeers());
            assertNotNull(auth.allowedMaterializations());
            assertNotNull(auth.secretAuthority());
        }

        @Test
        @DisplayName("GCP persona disallows DPC target type")
        void gcpRejectsDpcTarget() {
            assertFalse(gcpService.isTargetTypeAllowed("DPC_AIRFLOW_OPENSHIFT_SPARK"));
        }

        @Test
        @DisplayName("validateTargetType throws for persona-illegal type")
        void illegalTargetTypeThrows() {
            var ex = assertThrows(RuntimeAuthorityViolationException.class,
                    () -> gcpService.validateTargetType("DPC_AIRFLOW_OPENSHIFT_SPARK"));
            assertTrue(ex.getMessage().contains("DPC_AIRFLOW_OPENSHIFT_SPARK"));
            assertTrue(ex.getMessage().contains("GCP_PULSE"));
        }
    }

    // ================================================================
    // §2 — Runtime Binding CRUD + Validate with validationKind
    // ================================================================
    @Nested
    @DisplayName("§2 Runtime Binding CRUD and Validation")
    class RuntimeBindingCrudValidation {

        @Mock
        private RuntimeBindingRepository bindingRepo;

        private RuntimeBindingValidationService validationService;

        @BeforeEach
        void setUp() {
            validationService = new RuntimeBindingValidationService(bindingRepo);
        }

        @Test
        @DisplayName("Create binding defaults: validationKind=STUB, validationStatus=PENDING")
        void createBindingDefaults() {
            RuntimeBinding b = new RuntimeBinding();
            b.setEnvironment("dev");
            b.setBindingKind("GCP");
            assertEquals("STUB", b.getValidationKind());
            assertEquals("PENDING", b.getValidationStatus());
            assertTrue(b.isPrimary());
            assertTrue(b.isActive());
        }

        @Test
        @DisplayName("Stub validation sets validationKind=STUB and VALIDATED when roots complete")
        void stubValidationSuccess() {
            RuntimeBinding b = bindingWithRoots(TENANT, "dev", "GCP");
            when(bindingRepo.save(any(RuntimeBinding.class))).thenAnswer(inv -> inv.getArgument(0));

            RuntimeBinding validated = validationService.validate(b);

            assertEquals("VALIDATED", validated.getValidationStatus());
            assertEquals("STUB", validated.getValidationKind());
            assertNotNull(validated.getValidatedAt());
            assertNull(validated.getValidationError());
        }

        @Test
        @DisplayName("Stub validation sets FAILED when roots incomplete")
        void stubValidationFailed() {
            RuntimeBinding b = new RuntimeBinding();
            b.setEnvironment("dev");
            b.setBindingKind("GCP");
            when(bindingRepo.save(any(RuntimeBinding.class))).thenAnswer(inv -> inv.getArgument(0));

            RuntimeBinding validated = validationService.validate(b);

            assertEquals("FAILED", validated.getValidationStatus());
            assertEquals("STUB", validated.getValidationKind());
            assertEquals("Incomplete storage roots", validated.getValidationError());
        }

        @Test
        @DisplayName("LIVE_GCP validation request fails with overclaim guard")
        void liveGcpOverclaimGuard() {
            RuntimeBinding b = bindingWithRoots(TENANT, "dev", "GCP");
            when(bindingRepo.save(any(RuntimeBinding.class))).thenAnswer(inv -> inv.getArgument(0));

            RuntimeBinding validated = validationService.validate(b, "LIVE_GCP");

            assertEquals("FAILED", validated.getValidationStatus());
            assertEquals("LIVE_GCP", validated.getValidationKind());
            assertTrue(validated.getValidationError().contains("not yet implemented"));
            assertTrue(validated.getValidationError().contains("stub validation cannot claim live readiness"));
        }

        @Test
        @DisplayName("LIVE_HDFS validation request fails with overclaim guard")
        void liveHdfsOverclaimGuard() {
            RuntimeBinding b = bindingWithRoots(TENANT, "dev", "GCP");
            when(bindingRepo.save(any(RuntimeBinding.class))).thenAnswer(inv -> inv.getArgument(0));

            RuntimeBinding validated = validationService.validate(b, "LIVE_HDFS");

            assertEquals("FAILED", validated.getValidationStatus());
            assertEquals("LIVE_HDFS", validated.getValidationKind());
        }

        @Test
        @DisplayName("Illegal validationKind throws IllegalArgumentException")
        void illegalValidationKindThrows() {
            RuntimeBinding b = new RuntimeBinding();
            b.setEnvironment("dev");
            b.setBindingKind("GCP");

            assertThrows(IllegalArgumentException.class,
                    () -> validationService.validate(b, "FANTASY"));
        }

        @Test
        @DisplayName("validateBinding by ID returns ValidationResult with validationKind")
        void validateBindingById() {
            RuntimeBinding b = bindingWithRoots(TENANT, "dev", "GCP");
            b.setId("test-binding-id");
            when(bindingRepo.findById("test-binding-id")).thenReturn(Optional.of(b));
            when(bindingRepo.save(any(RuntimeBinding.class))).thenAnswer(inv -> inv.getArgument(0));

            ValidationResult result = validationService.validateBinding("test-binding-id");

            assertTrue(result.valid());
            assertEquals("STUB", result.validationKind());
            assertNotNull(result.validatedAt());
        }

        @Test
        @DisplayName("validateBinding by ID with LIVE_GCP returns failed result")
        void validateBindingByIdLiveGcp() {
            RuntimeBinding b = bindingWithRoots(TENANT, "dev", "GCP");
            b.setId("test-binding-id");
            when(bindingRepo.findById("test-binding-id")).thenReturn(Optional.of(b));
            when(bindingRepo.save(any(RuntimeBinding.class))).thenAnswer(inv -> inv.getArgument(0));

            ValidationResult result = validationService.validateBinding("test-binding-id", "LIVE_GCP");

            assertFalse(result.valid());
            assertEquals("LIVE_GCP", result.validationKind());
        }
    }

    // ================================================================
    // §3 — Authority Facade: PRIMARY vs DIAGNOSTIC Exclusion
    // ================================================================
    @Nested
    @DisplayName("§3 Authority Facade Resolution")
    class AuthorityFacadeResolution {

        @Mock
        private RuntimeBindingRepository bindingRepo;
        @Mock
        private DeploymentTargetRepository targetRepo;

        private RuntimeBindingAuthorityFacade facade;

        @BeforeEach
        void setUp() {
            facade = new RuntimeBindingAuthorityFacade(bindingRepo, targetRepo);
        }

        @Test
        @DisplayName("Active PRIMARY binding resolves successfully")
        void activePrimaryResolves() {
            RuntimeBinding primary = bindingWithRoots(TENANT, "integration", "GCP");
            when(bindingRepo.findActivePrimary("integration"))
                    .thenReturn(Optional.of(primary));

            var result = facade.resolvePrimaryBinding("integration");

            assertInstanceOf(ResolutionResult.Resolved.class, result);
            var resolved = ((ResolutionResult.Resolved<RuntimeBinding>) result).value();
            assertTrue(resolved.isPrimary());
            // BUG-39: bindings are deployment-global; no tenant field on entity.
            assertEquals("integration", resolved.getEnvironment());
        }

        @Test
        @DisplayName("DIAGNOSTIC binding is excluded from operational authority")
        void diagnosticExcluded() {
            RuntimeBinding diag = new RuntimeBinding();
            diag.setEnvironment("integration");
            diag.setBindingKind("GCP");
            diag.setSettingsRole("DIAGNOSTIC");
            diag.setRecordState("ACTIVE");
            when(bindingRepo.findActivePrimary("integration"))
                    .thenReturn(Optional.of(diag));

            var result = facade.resolvePrimaryBinding("integration");

            assertInstanceOf(ResolutionResult.Unresolved.class, result);
            var unresolved = (ResolutionResult.Unresolved<RuntimeBinding>) result;
            assertEquals("binding_missing", unresolved.blockerCode());
            assertTrue(unresolved.message().contains("DIAGNOSTIC"));
        }

        @Test
        @DisplayName("Missing binding returns Unresolved with binding_missing code")
        void missingBindingUnresolved() {
            when(bindingRepo.findActivePrimary("prod"))
                    .thenReturn(Optional.empty());

            var result = facade.resolvePrimaryBinding("prod");

            assertInstanceOf(ResolutionResult.Unresolved.class, result);
            assertEquals("binding_missing",
                    ((ResolutionResult.Unresolved<RuntimeBinding>) result).blockerCode());
        }

        @Test
        @DisplayName("Deployment target resolves for enabled target")
        void deploymentTargetResolves() {
            DeploymentTarget target = makeTarget(TENANT, "dev", "LOCAL_MATERIALIZATION");
            when(targetRepo.findByTenantIdAndEnabledTrueOrderByEnvironmentAsc(TENANT))
                    .thenReturn(List.of(target));

            var result = facade.resolvePrimaryDeploymentTarget(TENANT, "dev");

            assertInstanceOf(ResolutionResult.Resolved.class, result);
            assertEquals("LOCAL_MATERIALIZATION",
                    ((ResolutionResult.Resolved<DeploymentTarget>) result).value().getTargetType());
        }

        @Test
        @DisplayName("Missing deployment target returns Unresolved")
        void missingTargetUnresolved() {
            when(targetRepo.findByTenantIdAndEnabledTrueOrderByEnvironmentAsc(TENANT))
                    .thenReturn(List.of());

            var result = facade.resolvePrimaryDeploymentTarget(TENANT, "dev");

            assertInstanceOf(ResolutionResult.Unresolved.class, result);
            assertEquals("target_binding_missing",
                    ((ResolutionResult.Unresolved<DeploymentTarget>) result).blockerCode());
        }
    }

    // ================================================================
    // §4 — Deployment Target Persona Legality
    // ================================================================
    @Nested
    @DisplayName("§4 Deployment Target Persona Legality")
    class DeploymentTargetLegality {

        @Test
        @DisplayName("LOCAL_MATERIALIZATION is a legal target type")
        void localMaterializationLegal() {
            String normalized = DeploymentTarget.normalizeTargetType("LOCAL_MATERIALIZATION");
            assertEquals("LOCAL_MATERIALIZATION", normalized);
        }

        @Test
        @DisplayName("Legacy KUBERNETES normalizes to LOCAL_MATERIALIZATION")
        void kubernetesNormalizes() {
            assertEquals("LOCAL_MATERIALIZATION",
                    DeploymentTarget.normalizeTargetType("KUBERNETES"));
        }

        @Test
        @DisplayName("GCP_COMPOSER_DATAPROC is a legal target type")
        void gcpComposerDataprocLegal() {
            assertEquals("GCP_COMPOSER_DATAPROC",
                    DeploymentTarget.normalizeTargetType("GCP_COMPOSER_DATAPROC"));
        }

        @Test
        @DisplayName("Null/blank normalizes to LOCAL_MATERIALIZATION")
        void nullNormalizesToLocal() {
            assertEquals("LOCAL_MATERIALIZATION", DeploymentTarget.normalizeTargetType(null));
            assertEquals("LOCAL_MATERIALIZATION", DeploymentTarget.normalizeTargetType(""));
            assertEquals("LOCAL_MATERIALIZATION", DeploymentTarget.normalizeTargetType("  "));
        }

        @Test
        @DisplayName("Illegal target type FANTASY_RUNTIME is rejected by authority")
        void illegalTargetTypeRejected() {
            RuntimeAuthorityProperties props = new RuntimeAuthorityProperties();
            props.setActivePersona("GCP_PULSE");
            RuntimeAuthorityService service = new RuntimeAuthorityService(props);
            service.initialize();

            assertThrows(RuntimeAuthorityViolationException.class,
                    () -> service.validateTargetType("FANTASY_RUNTIME"));
        }
    }

    // ================================================================
    // §5 — Tenant Readiness: runtimeBinding Category
    // ================================================================
    @Nested
    @DisplayName("§5 Tenant Readiness: runtimeBinding")
    class ReadinessRuntimeBinding {

        @Mock
        private RuntimeBindingRepository bindingRepo;
        @Mock
        private DeploymentTargetRepository targetRepo;

        private RuntimeAuthorityService authorityService;
        private TenantRuntimeReadinessService readinessService;

        @BeforeEach
        void setUp() {
            RuntimeAuthorityProperties props = new RuntimeAuthorityProperties();
            props.setActivePersona("GCP_PULSE");
            authorityService = new RuntimeAuthorityService(props);
            authorityService.initialize();
            readinessService = new TenantRuntimeReadinessService(
                    bindingRepo, targetRepo, authorityService);
        }

        @Test
        @DisplayName("Ready when active PRIMARY bindings exist with VALIDATED status")
        void readyWithValidatedBindings() {
            RuntimeBinding b = bindingWithRoots(TENANT, "dev", "GCP");
            b.setValidationStatus("VALIDATED");
            b.setValidationKind("STUB");
            when(bindingRepo.findAllByOrderByEnvironmentAsc())
                    .thenReturn(List.of(b));

            Map<String, Object> cat = readinessService.buildRuntimeBindingCategory(TENANT);

            assertEquals("ready", cat.get("status"));
            assertEquals(1, cat.get("activePrimaryCount"));
            assertTrue((Boolean) cat.get("anyValidated"));
            assertFalse((Boolean) cat.get("gcpLiveReady"),
                    "STUB validation must not claim GCP live readiness");
            assertFalse(cat.containsKey("blockers"));
        }

        @Test
        @DisplayName("Incomplete when PENDING validation")
        void incompleteWhenPending() {
            RuntimeBinding b = bindingWithRoots(TENANT, "dev", "GCP");
            // Default status = PENDING
            when(bindingRepo.findAllByOrderByEnvironmentAsc())
                    .thenReturn(List.of(b));

            Map<String, Object> cat = readinessService.buildRuntimeBindingCategory(TENANT);

            assertEquals("incomplete", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(s -> s.contains("not been validated")));
        }

        @Test
        @DisplayName("not_configured when no PRIMARY bindings exist")
        void notConfiguredWhenEmpty() {
            when(bindingRepo.findAllByOrderByEnvironmentAsc())
                    .thenReturn(List.of());

            Map<String, Object> cat = readinessService.buildRuntimeBindingCategory(TENANT);

            assertEquals("not_configured", cat.get("status"));
            assertEquals(0, cat.get("activePrimaryCount"));
        }

        @Test
        @DisplayName("DIAGNOSTIC bindings are excluded from readiness")
        void diagnosticExcluded() {
            RuntimeBinding diag = new RuntimeBinding();
            diag.setEnvironment("dev");
            diag.setBindingKind("GCP");
            diag.setSettingsRole("DIAGNOSTIC");
            diag.setRecordState("ACTIVE");
            when(bindingRepo.findAllByOrderByEnvironmentAsc())
                    .thenReturn(List.of(diag));

            Map<String, Object> cat = readinessService.buildRuntimeBindingCategory(TENANT);

            assertEquals("not_configured", cat.get("status"));
            assertEquals(0, cat.get("activePrimaryCount"));
        }

        @Test
        @DisplayName("STUB-validated binding: gcpLiveReady is always false")
        void stubCannotClaimGcpLive() {
            RuntimeBinding b = bindingWithRoots(TENANT, "dev", "GCP");
            b.setValidationStatus("VALIDATED");
            b.setValidationKind("STUB");
            when(bindingRepo.findAllByOrderByEnvironmentAsc())
                    .thenReturn(List.of(b));

            Map<String, Object> cat = readinessService.buildRuntimeBindingCategory(TENANT);

            assertFalse((Boolean) cat.get("gcpLiveReady"),
                    "STUB validation MUST NOT claim GCP live readiness");
        }

        @Test
        @DisplayName("LIVE_GCP-validated binding: gcpLiveReady is true")
        void liveGcpClaimsReady() {
            RuntimeBinding b = bindingWithRoots(TENANT, "dev", "GCP");
            b.setValidationStatus("VALIDATED");
            b.setValidationKind("LIVE_GCP");
            when(bindingRepo.findAllByOrderByEnvironmentAsc())
                    .thenReturn(List.of(b));

            Map<String, Object> cat = readinessService.buildRuntimeBindingCategory(TENANT);

            assertTrue((Boolean) cat.get("gcpLiveReady"));
        }

        @Test
        @DisplayName("Incomplete storage roots surface as blocker")
        void incompleteRootsBlocker() {
            RuntimeBinding b = new RuntimeBinding();
            b.setEnvironment("dev");
            b.setBindingKind("GCP");
            b.setValidationStatus("VALIDATED");
            b.setValidationKind("STUB");
            // No storage roots
            when(bindingRepo.findAllByOrderByEnvironmentAsc())
                    .thenReturn(List.of(b));

            Map<String, Object> cat = readinessService.buildRuntimeBindingCategory(TENANT);

            assertEquals("incomplete", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(s -> s.contains("incomplete storage roots")));
        }
    }

    // ================================================================
    // §6 — Tenant Readiness: deploymentTarget Category
    // ================================================================
    @Nested
    @DisplayName("§6 Tenant Readiness: deploymentTarget")
    class ReadinessDeploymentTarget {

        @Mock
        private RuntimeBindingRepository bindingRepo;
        @Mock
        private DeploymentTargetRepository targetRepo;

        private RuntimeAuthorityService authorityService;
        private TenantRuntimeReadinessService readinessService;

        @BeforeEach
        void setUp() {
            RuntimeAuthorityProperties props = new RuntimeAuthorityProperties();
            props.setActivePersona("GCP_PULSE");
            authorityService = new RuntimeAuthorityService(props);
            authorityService.initialize();
            readinessService = new TenantRuntimeReadinessService(
                    bindingRepo, targetRepo, authorityService);
        }

        @Test
        @DisplayName("Ready when enabled target has persona-legal type")
        void readyWithLegalTarget() {
            DeploymentTarget t = makeTarget(TENANT, "dev", "LOCAL_MATERIALIZATION");
            when(targetRepo.findByTenantIdOrderByEnvironmentAsc(TENANT))
                    .thenReturn(List.of(t));

            Map<String, Object> cat = readinessService.buildDeploymentTargetCategory(TENANT);

            assertEquals("ready", cat.get("status"));
            assertEquals(1, cat.get("enabledCount"));
            assertEquals("GCP_PULSE", cat.get("activePersona"));
            assertFalse(cat.containsKey("blockers"));
        }

        @Test
        @DisplayName("Incomplete when target has persona-illegal type")
        void incompleteWithIllegalType() {
            DeploymentTarget t = makeTarget(TENANT, "dev", "DPC_AIRFLOW_OPENSHIFT_SPARK");
            when(targetRepo.findByTenantIdOrderByEnvironmentAsc(TENANT))
                    .thenReturn(List.of(t));

            Map<String, Object> cat = readinessService.buildDeploymentTargetCategory(TENANT);

            assertEquals("incomplete", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(
                    s -> s.contains("DPC_AIRFLOW_OPENSHIFT_SPARK") && s.contains("illegal")));
        }

        @Test
        @DisplayName("not_configured when no enabled targets exist")
        void notConfiguredWhenEmpty() {
            when(targetRepo.findByTenantIdOrderByEnvironmentAsc(TENANT))
                    .thenReturn(List.of());

            Map<String, Object> cat = readinessService.buildDeploymentTargetCategory(TENANT);

            assertEquals("not_configured", cat.get("status"));
            assertEquals(0, cat.get("enabledCount"));
        }

        @Test
        @DisplayName("Disabled targets are excluded from readiness")
        void disabledExcluded() {
            DeploymentTarget t = makeTarget(TENANT, "dev", "LOCAL_MATERIALIZATION");
            t.setEnabled(false);
            when(targetRepo.findByTenantIdOrderByEnvironmentAsc(TENANT))
                    .thenReturn(List.of(t));

            Map<String, Object> cat = readinessService.buildDeploymentTargetCategory(TENANT);

            assertEquals("not_configured", cat.get("status"));
            assertEquals(0, cat.get("enabledCount"));
        }

        @Test
        @DisplayName("Target summaries include personaLegal flag")
        void targetSummaryIncludesLegalFlag() {
            DeploymentTarget legal = makeTarget(TENANT, "dev", "LOCAL_MATERIALIZATION");
            when(targetRepo.findByTenantIdOrderByEnvironmentAsc(TENANT))
                    .thenReturn(List.of(legal));

            Map<String, Object> cat = readinessService.buildDeploymentTargetCategory(TENANT);

            @SuppressWarnings("unchecked")
            var targets = (List<Map<String, Object>>) cat.get("targets");
            assertEquals(1, targets.size());
            assertTrue((Boolean) targets.get(0).get("personaLegal"));
        }
    }

    // ================================================================
    // §7 — Runtime Binding Service: Persona Guard
    // ================================================================
    @Nested
    @DisplayName("§7 Runtime Binding Persona Guard")
    class BindingPersonaGuard {

        @Mock
        private RuntimeBindingRepository bindingRepo;

        private RuntimeAuthorityService authorityService;
        private RuntimeBindingService bindingService;

        @BeforeEach
        void setUp() {
            RuntimeAuthorityProperties props = new RuntimeAuthorityProperties();
            props.setActivePersona("GCP_PULSE");
            authorityService = new RuntimeAuthorityService(props);
            authorityService.initialize();
            bindingService = new RuntimeBindingService(bindingRepo, authorityService);
        }

        @Test
        @DisplayName("PRIMARY GCP binding for non-local env succeeds under GCP persona")
        void primaryGcpAllowed() {
            RuntimeBinding b = bindingWithRoots(TENANT, "integration", "GCP");
            when(bindingRepo.save(any(RuntimeBinding.class))).thenAnswer(inv -> inv.getArgument(0));

            RuntimeBinding saved = bindingService.create(b);

            assertEquals("GCP", saved.getBindingKind());
            assertTrue(saved.isPrimary());
        }

        @Test
        @DisplayName("PRIMARY DPC binding for non-local env is rejected under GCP persona")
        void primaryDpcRejected() {
            RuntimeBinding b = new RuntimeBinding();
            b.setEnvironment("integration");
            b.setBindingKind("DPC");
            b.setSettingsRole("PRIMARY");

            assertThrows(RuntimeAuthorityViolationException.class,
                    () -> bindingService.create(b));
        }

        @Test
        @DisplayName("LOCAL environment bypasses persona guard")
        void localBypassesGuard() {
            RuntimeBinding b = new RuntimeBinding();
            b.setEnvironment("local");
            b.setBindingKind("DPC");
            b.setSettingsRole("PRIMARY");
            when(bindingRepo.save(any(RuntimeBinding.class))).thenAnswer(inv -> inv.getArgument(0));

            RuntimeBinding saved = bindingService.create(b);

            assertEquals("DPC", saved.getBindingKind());
        }
    }

    // ================================================================
    // §8 — Package Deploy Preflight NOT Required/Executed
    // ================================================================
    @Nested
    @DisplayName("§8 Package Deploy Preflight Not Required")
    class PackageDeployPreflightNotRequired {

        @Test
        @DisplayName("PKT-0014 does not invoke or require package deploy preflight")
        void preflightNotRequired() {
            // This test documents the contract: PKT-0014 proves tenant
            // runtime/deployment readiness independent of package-level
            // deploy preflight. Preflight is owned by the deploy flow
            // (DeploymentPreflightService) and fires only during
            // POST /api/v1/packages/{id}/deploy.
            //
            // PKT-0014 readiness categories (runtimeBinding, deploymentTarget)
            // are prerequisites that inform preflight but do not execute it.
            //
            // Verification: no DeploymentPreflightService dependency exists
            // in TenantRuntimeReadinessService.
            assertDoesNotThrow(() -> {
                // TenantRuntimeReadinessService has no preflight dependency
                var fields = TenantRuntimeReadinessService.class.getDeclaredFields();
                for (var f : fields) {
                    assertFalse(f.getType().getSimpleName().contains("Preflight"),
                            "TenantRuntimeReadinessService must not depend on preflight: "
                                    + f.getName());
                }
            });
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static RuntimeBinding bindingWithRoots(String tenantId, String env, String kind) {
        RuntimeBinding b = new RuntimeBinding();
        b.setEnvironment(env);
        b.setBindingKind(kind);
        b.setSettingsRole("PRIMARY");
        b.setRecordState("ACTIVE");
        b.setStorageRootFiles("gs://" + tenantId + "-files");
        b.setStorageRootLake("gs://" + tenantId + "-lake");
        b.setStorageRootOps("gs://" + tenantId + "-ops");
        return b;
    }

    private static DeploymentTarget makeTarget(String tenantId, String env, String targetType) {
        DeploymentTarget t = new DeploymentTarget();
        t.setTenantId(tenantId);
        t.setName(env + "-target");
        t.setEnvironment(env);
        t.setTargetType(targetType);
        t.setEnabled(true);
        return t;
    }
}
