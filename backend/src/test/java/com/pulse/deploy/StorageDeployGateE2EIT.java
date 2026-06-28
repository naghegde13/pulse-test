package com.pulse.deploy;

import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.auth.repository.UserRepository;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.preflight.DeploymentPreflightService;
import com.pulse.deploy.preflight.PreflightCheckCode;
import com.pulse.deploy.preflight.PreflightCheckResult;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.storage.StorageBackendDeployGate;
import com.pulse.storage.model.StorageBackend;
import com.pulse.storage.repository.StorageBackendRepository;
import com.pulse.support.SeedFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for the storage-backend deploy gate
 * (TASK_P0_storage_deploy_gate_e2e).
 *
 * <p><b>Why this exists.</b> {@link StorageBackendDeployGate} is covered by
 * a unit test ({@code StorageBackendDeployGateTest}) that mocks every
 * repository, and {@link DeploymentPreflightService} is covered by its own
 * contract test ({@code DeploymentPreflightServiceContractTest}) that
 * mocks the gate. Neither test catches a regression where the gate would
 * stop being wired into the preflight surface — e.g. a refactor that
 * swaps in a no-op gate bean, or a config change that breaks the
 * (tenant, env, backend) lookup against a real persistence context. This
 * IT closes that gap by booting the full Spring context, seeding real
 * {@code storage_backends} + pipeline composition rows through the
 * canonical {@link SeedFixtures}, and exercising the gate through both
 * its public {@code check(...)} method and through the Spring-managed
 * {@link DeploymentPreflightService} that the deploy controller routes
 * through.
 *
 * <p><b>Test boundary.</b> The spec asked for "service_to_service" and
 * mentioned routing through {@code DeployController#deploy}. The
 * controller's deploy path additionally requires resolvable git
 * provenance (Phase 2), a runtime-capability profile (Phase 7), a
 * complete static-runtime assessment, an enabled deployment target with
 * a known {@code target_type}, an actor context, and a clean working
 * tree — concerns that are exercised by their own contract tests and
 * have nothing to do with the storage gate. Coupling all of them into
 * this IT would turn unrelated regressions into "storage gate" failures
 * and vice versa. So we exercise the gate end-to-end at the two
 * surfaces that uniquely matter for gate enforcement:
 * <ol>
 *   <li>Direct: {@link StorageBackendDeployGate#check(String, String)} —
 *       proves the gate evaluates real seeded rows correctly through
 *       its actual Spring-managed repository wiring.</li>
 *   <li>Preflight: {@link DeploymentPreflightService#check(String, String,
 *       Instant, com.pulse.auth.policy.CallerContext, String)} —
 *       proves the gate's verdict surfaces as a
 *       {@link PreflightCheckCode#STORAGE_BACKEND_VALIDATED} outcome on
 *       the preflight result, which is exactly the payload the deploy
 *       controller persists on
 *       {@code Deployment.metadata.preflightBlockers}. A regression that
 *       decouples the gate from preflight will fail these assertions
 *       before it reaches the controller.</li>
 * </ol>
 *
 * <p>The preflight call seeds a minimal {@link Package} +
 * {@link DeploymentTarget} so it can run end-to-end. Other preflight
 * checks (provenance, static deployability, runtime capability, agent
 * audit context, etc.) will produce their own outcomes — we
 * intentionally do not assert on those here; this test asserts only on
 * the {@code STORAGE_BACKEND_VALIDATED} outcome so other phases'
 * regressions don't flake this gate test.
 *
 * <p><b>Pinned current behavior (LOCKED rules from
 * {@link StorageBackendDeployGate}'s javadoc and the unit test).</b>
 * The gate's rule is uniform across all envs: every
 * {@code (tenant_id, environment, backend)} row used by any
 * {@link SubPipelineInstance} on the pipeline must have
 * {@code provisioning_status = 'validated'}. There is intentionally no
 * "dev allows stub" carve-out at the gate level — local-dev MinIO is
 * handled by V96 seeding the seed-tenant rows as {@code validated} from
 * the start. The env-matrix test below pins this current rule (same
 * non-validated row → same gate verdict in dev as in prod) and notes
 * follow-up work if the rule changes.
 *
 * <p>This class lives under {@code com.pulse.deploy} and ends in
 * {@code IT} so the {@code backendIntegrationTest} gradle task picks it
 * up. The {@code @ActiveProfiles("test")} matches the existing
 * deploy-package IT pattern in {@code RepresentativeStaticDeployabilityProofIT}
 * (H2 + create-drop; Flyway off — sufficient because the gate's logic is
 * tenant-scoped JPA queries, not Postgres-specific migration semantics).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StorageDeployGateE2EIT {

    @Autowired private StorageBackendDeployGate gate;
    @Autowired private DeploymentPreflightService preflightService;
    @Autowired private StorageBackendRepository storageBackendRepo;
    @Autowired private PackageRepository packageRepository;
    @Autowired private DeploymentTargetRepository deploymentTargetRepository;

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DomainRepository domainRepository;
    @Autowired private SystemOfRecordRepository sorRepository;
    @Autowired private ConnectorDefinitionRepository connectorDefinitionRepository;
    @Autowired private ConnectorInstanceRepository connectorInstanceRepository;
    @Autowired private DatasetRepository datasetRepository;
    @Autowired private BlueprintRepository blueprintRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private PipelineVersionRepository pipelineVersionRepository;
    @Autowired private SubPipelineInstanceRepository subPipelineInstanceRepository;
    @Autowired private PortWiringRepository portWiringRepository;

    private SeedFixtures seedFixtures() {
        return new SeedFixtures(
                tenantRepository, userRepository, domainRepository, sorRepository,
                connectorDefinitionRepository, connectorInstanceRepository, datasetRepository,
                blueprintRepository, pipelineRepository, pipelineVersionRepository,
                subPipelineInstanceRepository, portWiringRepository);
    }

    // ------------------------------------------------------------------
    //  TC_storage_gate_blocks_invalid_backend
    // ------------------------------------------------------------------

    /**
     * Pending storage_backends row → gate rejects with a {@code pending}
     * blocker, and the preflight surface re-publishes the same verdict
     * under {@link PreflightCheckCode#STORAGE_BACKEND_VALIDATED}. This is
     * the canonical production-write hazard the gate is meant to catch.
     */
    @Test
    void blocksDeployAgainstPendingBackend() {
        SeedFixtures fx = seedFixtures();
        SeedFixtures.Context ctx = fx.seedFullPipelineContext();
        SubPipelineInstance instance = seedDpcInstance(fx, ctx);
        assertEquals("DPC", instance.getStorageBackend(),
                "SeedFixtures must default storage_backend=DPC so the gate has a backend to evaluate");

        seedStorageBackendRow(ctx.tenantId(), "prod", "DPC", "pending");

        StorageBackendDeployGate.Result result = gate.check(ctx.pipelineId(), "prod");

        assertFalse(result.ok(), "Gate must reject deploy against a pending backend");
        assertEquals(1, result.blockers().size(),
                "Exactly one blocker for the single pending backend, got: " + result.blockers());
        StorageBackendDeployGate.Blocker blocker = result.blockers().get(0);
        assertEquals("DPC", blocker.backend());
        assertEquals("prod", blocker.environment());
        assertEquals("pending", blocker.reason());
        assertTrue(result.reason().contains("Contact the platform team"),
                "Pending blocker must hint at the operator action; got: " + result.reason());

        // Wire the same scenario through the preflight surface. We
        // assert only on the STORAGE_BACKEND_VALIDATED outcome — other
        // preflight checks may pass or fail depending on Phase
        // provenance/capability state, and are exercised elsewhere.
        PreflightCheckResult.CheckOutcome storageCheck =
                preflightStorageOutcome(ctx, "prod");
        assertEquals(PreflightCheckResult.FAIL, storageCheck.status(),
                "Preflight must surface the gate rejection as STORAGE_BACKEND_VALIDATED=FAIL");
        assertNotNull(storageCheck.message());
        assertTrue(storageCheck.message().contains("pending"),
                "Preflight message must surface the gate's pending reason; got: " + storageCheck.message());
    }

    // ------------------------------------------------------------------
    //  TC_storage_gate_allows_validated_backend
    // ------------------------------------------------------------------

    /**
     * Validated storage_backends row → gate accepts. False-rejection
     * regression breaks every deploy, so this positive case is just as
     * important as the negative ones.
     */
    @Test
    void allowsDeployAgainstValidatedBackend() {
        SeedFixtures fx = seedFixtures();
        SeedFixtures.Context ctx = fx.seedFullPipelineContext();
        seedDpcInstance(fx, ctx);
        seedStorageBackendRow(ctx.tenantId(), "prod", "DPC", "validated");

        StorageBackendDeployGate.Result result = gate.check(ctx.pipelineId(), "prod");

        assertTrue(result.ok(),
                "Gate must allow deploy when every backend is validated; got: " + result.reason());
        assertEquals("ok", result.reason());

        PreflightCheckResult.CheckOutcome storageCheck =
                preflightStorageOutcome(ctx, "prod");
        assertEquals(PreflightCheckResult.PASS, storageCheck.status(),
                "Preflight must surface the gate acceptance as STORAGE_BACKEND_VALIDATED=PASS");
    }

    // ------------------------------------------------------------------
    //  TC_storage_gate_disabled_backend_blocks
    // ------------------------------------------------------------------

    /**
     * Disabled backend → gate rejects with a {@code disabled}-reason
     * blocker carrying the "choose a different backend or re-enable"
     * hint. Operator escape hatch must keep working.
     */
    @Test
    void blocksDeployAgainstDisabledBackend() {
        SeedFixtures fx = seedFixtures();
        SeedFixtures.Context ctx = fx.seedFullPipelineContext();
        seedDpcInstance(fx, ctx);
        seedStorageBackendRow(ctx.tenantId(), "prod", "DPC", "disabled");

        StorageBackendDeployGate.Result result = gate.check(ctx.pipelineId(), "prod");

        assertFalse(result.ok());
        assertEquals(1, result.blockers().size(), "got: " + result.blockers());
        StorageBackendDeployGate.Blocker blocker = result.blockers().get(0);
        assertEquals("disabled", blocker.reason(),
                "Disabled-backend blocker must surface 'disabled' as the structured reason");
        assertTrue(blocker.message().contains("disabled"),
                "Human-readable message must mention 'disabled'; got: " + blocker.message());
        assertTrue(result.reason().contains("disabled"),
                "Aggregate reason must mention 'disabled'; got: " + result.reason());

        PreflightCheckResult.CheckOutcome storageCheck =
                preflightStorageOutcome(ctx, "prod");
        assertEquals(PreflightCheckResult.FAIL, storageCheck.status());
        assertTrue(storageCheck.message().contains("disabled"),
                "Preflight must surface the disabled reason; got: " + storageCheck.message());
    }

    // ------------------------------------------------------------------
    //  TC_storage_gate_environment_matrix
    // ------------------------------------------------------------------

    /**
     * Environment-matrix behavior (LOCKED current rule).
     *
     * <p>The gate's rule is uniform across every env: validated → pass,
     * anything else → block. There is intentionally no "dev allows
     * stub / prod requires validated" carve-out at the gate level — the
     * gate's javadoc explicitly calls this out and the unit test
     * {@code uniformRule_devAlsoRequiresValidated} pins it.
     *
     * <p>This integration test re-pins that rule end-to-end against the
     * real persistence layer: a single backend that is validated in dev
     * but not in prod produces opposite gate decisions (dev allowed,
     * prod blocked) because the gate scopes its lookup by canonical env
     * — NOT because the gate has env-dependent rules. The "matrix" the
     * spec asked for is therefore the env-scoped row matrix, not a
     * separate per-env policy.
     *
     * <p>If a future spec change introduces an "allow stub in dev" carve
     * out at the gate level, this test must be updated and the change
     * tracked as a deliberate snapshot update — see the task packet's
     * {@code risks.risk_gate_rules_in_flux}.
     */
    @Test
    void environmentMatrix_devAllowedProdBlocked_underUniformRule() {
        SeedFixtures fx = seedFixtures();
        SeedFixtures.Context ctx = fx.seedFullPipelineContext();
        seedDpcInstance(fx, ctx);

        // Dev row exists and is validated (the V96 seed pattern for
        // local development); prod row exists but is still pending.
        seedStorageBackendRow(ctx.tenantId(), "dev", "DPC", "validated");
        seedStorageBackendRow(ctx.tenantId(), "prod", "DPC", "pending");

        StorageBackendDeployGate.Result devResult = gate.check(ctx.pipelineId(), "dev");
        assertTrue(devResult.ok(),
                "Dev deploy must pass when its dev row is validated; got: " + devResult.reason());

        StorageBackendDeployGate.Result prodResult = gate.check(ctx.pipelineId(), "prod");
        assertFalse(prodResult.ok(),
                "Prod deploy must fail under the uniform rule when the prod row is not validated");
        assertEquals(1, prodResult.blockers().size());
        assertEquals("prod", prodResult.blockers().get(0).environment(),
                "Prod blocker must scope to the canonical 'prod' env key");
        assertEquals("pending", prodResult.blockers().get(0).reason());

        // Inverse: when dev is also non-validated, dev MUST block too —
        // proving the gate has no env-specific carve-out for dev. This
        // is the locked-rule guard rail.
        StorageBackend devRow = storageBackendRepo
                .findByTenantIdAndEnvironmentAndBackend(ctx.tenantId(), "dev", "DPC")
                .orElseThrow();
        devRow.setProvisioningStatus("pending");
        devRow.setProvisioningValidatedAt(null);
        storageBackendRepo.save(devRow);

        StorageBackendDeployGate.Result devReBlocked = gate.check(ctx.pipelineId(), "dev");
        assertFalse(devReBlocked.ok(),
                "Dev must be gated identically to prod once its row is non-validated"
                        + " (uniform rule, locked); got: " + devReBlocked.reason());
    }

    // ------------------------------------------------------------------
    //  TC_storage_gate_path_placeholder_resolution
    // ------------------------------------------------------------------

    /**
     * Canonical-input resolution feeds the gate consistently.
     *
     * <p>The spec's "placeholder path" wording is a slight misnomer for
     * the current architecture: the gate does NOT consume rendered
     * pipeline paths (those are produced by
     * {@code StoragePlaceholderResolver} downstream of the gate). What
     * the gate DOES resolve are its lookup keys — specifically, the
     * caller-supplied environment string is normalized to its canonical
     * lowercase form via {@link com.pulse.deploy.environment.DeploymentEnvironment}
     * before {@code (tenant_id, environment, backend)} lookup. A legacy
     * uppercase input ({@code DEV}, {@code PROD}, {@code INTEGRATION},
     * etc.) must therefore reach the same {@code storage_backends} row
     * as the canonical lowercase form, producing identical gate
     * verdicts.
     *
     * <p>This test pins that resolution end-to-end against real seeded
     * rows: legacy ↔ canonical env inputs map to the same row and
     * produce identical Results. A regression that bypasses env
     * normalization at the gate boundary would split the canonical row
     * from its legacy-form alias and the inputs would diverge.
     */
    @Test
    void placeholderResolution_legacyAndCanonicalEnvInputsFeedSameRow() {
        SeedFixtures fx = seedFixtures();
        SeedFixtures.Context ctx = fx.seedFullPipelineContext();
        seedDpcInstance(fx, ctx);

        // Only one row exists for canonical 'dev'; legacy 'DEV' must
        // resolve to it via the gate's input normalization.
        seedStorageBackendRow(ctx.tenantId(), "dev", "DPC", "validated");
        // Only one row exists for canonical 'integration'; legacy 'INT'
        // and 'INTEGRATION' must both resolve to it.
        seedStorageBackendRow(ctx.tenantId(), "integration", "DPC", "validated");

        StorageBackendDeployGate.Result canonicalDev = gate.check(ctx.pipelineId(), "dev");
        StorageBackendDeployGate.Result legacyDev = gate.check(ctx.pipelineId(), "DEV");
        assertTrue(canonicalDev.ok(),
                "Canonical 'dev' must resolve and pass; got: " + canonicalDev.reason());
        assertTrue(legacyDev.ok(),
                "Legacy 'DEV' must resolve to the same row as canonical 'dev' and pass;"
                        + " got: " + legacyDev.reason());
        assertEquals(canonicalDev.ok(), legacyDev.ok(),
                "Gate decision must be identical for legacy vs canonical env inputs");

        StorageBackendDeployGate.Result canonicalIntegration =
                gate.check(ctx.pipelineId(), "integration");
        StorageBackendDeployGate.Result legacyIntShort = gate.check(ctx.pipelineId(), "INT");
        StorageBackendDeployGate.Result legacyIntLong = gate.check(ctx.pipelineId(), "INTEGRATION");
        assertTrue(canonicalIntegration.ok());
        assertTrue(legacyIntShort.ok(),
                "Legacy 'INT' must resolve to the same row as canonical 'integration'");
        assertTrue(legacyIntLong.ok(),
                "Legacy 'INTEGRATION' must resolve to the same row as canonical 'integration'");

        // Negative inverse: an unknown env (typo, deprecated name) must
        // produce a structured 'unknown_environment' blocker rather
        // than silently missing the row — proving the normalization
        // boundary, not row absence, owns the rejection.
        StorageBackendDeployGate.Result unknown = gate.check(ctx.pipelineId(), "STAGING");
        assertFalse(unknown.ok());
        assertEquals(1, unknown.blockers().size());
        assertEquals("unknown_environment", unknown.blockers().get(0).reason(),
                "Unknown env input must surface as 'unknown_environment' via the canonical"
                        + " normalization boundary; got: " + unknown.reason());
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /**
     * Seed a SubPipelineInstance on the pipeline. The minimal blueprint
     * row is required because {@code SeedFixtures.seedSubPipelineInstance}
     * persists the {@code blueprint_id} foreign key. The instance's
     * {@code storage_backend} defaults to "DPC" — which is exactly what
     * the gate looks up.
     */
    private SubPipelineInstance seedDpcInstance(SeedFixtures fx, SeedFixtures.Context ctx) {
        var blueprint = fx.seedBlueprint(
                "StorageGateITSource-" + SeedFixtures.nextSuffix(),
                com.pulse.blueprint.model.BlueprintCategory.INGESTION,
                java.util.List.of(),
                java.util.List.of(Map.of("name", "raw_output")));
        return fx.seedSubPipelineInstance(
                ctx.pipelineId(),
                ctx.versionId(),
                blueprint.getId(),
                blueprint.getBlueprintKey(),
                "storage-gate-it-source",
                1,
                Map.of());
    }

    /** Persist a storage_backends row in the requested state. */
    private StorageBackend seedStorageBackendRow(String tenantId, String env, String backend, String status) {
        StorageBackend row = new StorageBackend();
        row.setTenantId(tenantId);
        row.setEnvironment(env);
        row.setBackend(backend);
        row.setStorageRootFiles("pulse-it-" + env + "-files");
        row.setStorageRootLake("pulse-it-" + env + "-lake");
        if ("GCP".equals(backend)) {
            row.setGcpProject("pulse-it-" + env);
        } else {
            row.setDpcScheme("s3a");
            row.setDpcCluster("pulse-dpc-it-" + env);
        }
        row.setProvisioningStatus(status);
        if ("validated".equals(status)) {
            row.setProvisioningValidatedAt(Instant.now());
        }
        return storageBackendRepo.save(row);
    }

    /**
     * Drive the preflight service end-to-end against a minimal Package
     * + DeploymentTarget seeded just for this assertion, and return the
     * {@code STORAGE_BACKEND_VALIDATED} outcome. All other check
     * outcomes are intentionally ignored — they are covered by their
     * own contract tests and depend on Phase 2 / Phase 7 plumbing
     * unrelated to the storage gate.
     */
    private PreflightCheckResult.CheckOutcome preflightStorageOutcome(SeedFixtures.Context ctx, String env) {
        Package pkg = seedMinimalPackage(ctx);
        DeploymentTarget target = seedMinimalTarget(ctx, env);
        CallerContext caller = new CallerContext(
                ctx.userId(), ctx.tenantId(), Set.of(), CallerSurface.UI);
        PreflightCheckResult preflight = preflightService.check(
                pkg.getId(), target.getId(), Instant.now(), caller, "it-corr-" + SeedFixtures.nextSuffix());
        Optional<PreflightCheckResult.CheckOutcome> outcome = preflight.checks().stream()
                .filter(c -> PreflightCheckCode.STORAGE_BACKEND_VALIDATED.name().equals(c.code()))
                .findFirst();
        assertTrue(outcome.isPresent(),
                "Preflight result must include a STORAGE_BACKEND_VALIDATED check outcome; got: "
                        + preflight.checks().stream().map(PreflightCheckResult.CheckOutcome::code).toList());
        return outcome.get();
    }

    /**
     * Seed a minimal Package row sufficient for preflight to resolve
     * and reach the storage-gate check. We do not populate Phase 2 git
     * provenance / static-runtime assessment / capability profile —
     * those checks will fail with their own outcomes which we ignore.
     */
    private Package seedMinimalPackage(SeedFixtures.Context ctx) {
        Package pkg = new Package();
        pkg.setPipelineId(ctx.pipelineId());
        pkg.setVersionId(ctx.versionId());
        pkg.setTenantId(ctx.tenantId());
        pkg.setPackageType("ARTIFACT_BUNDLE");
        pkg.setBuildStatus("COMPLETED");
        pkg.setBuiltBy(ctx.userId());
        pkg.setBuiltAt(Instant.now());
        return packageRepository.save(pkg);
    }

    /**
     * Seed a minimal DeploymentTarget row for the requested env. The
     * target only needs to resolve so {@code checkStorageBackendValidated}
     * can call the gate with the right canonical env key.
     */
    private DeploymentTarget seedMinimalTarget(SeedFixtures.Context ctx, String env) {
        DeploymentTarget target = new DeploymentTarget();
        target.setTenantId(ctx.tenantId());
        target.setName("storage-gate-it-target-" + SeedFixtures.nextSuffix());
        target.setEnvironment(env);
        target.setTargetType("LOCAL_MATERIALIZATION");
        target.setConfig(Map.of());
        target.setEnabled(true);
        return deploymentTargetRepository.save(target);
    }
}
