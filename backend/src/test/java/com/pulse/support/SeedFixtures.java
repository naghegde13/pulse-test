package com.pulse.support;

import com.pulse.auth.model.PulseUser;
import com.pulse.auth.model.Tenant;
import com.pulse.auth.model.UserRole;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.auth.repository.UserRepository;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineStage;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.sor.model.ConnectorDefinition;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.ConnectorType;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.model.Domain;
import com.pulse.sor.model.ReleaseStage;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Canonical shared backend test seed.
 *
 * <p>Wave 0 / TASK_P0_test_infra_shared_fixtures. Six P0 downstream tasks share this seed
 * shape so chat, composition, codegen, deploy, and approval tests do not each invent their
 * own tenant/user/dataset rows.
 *
 * <p>Scope:
 * <ul>
 *   <li>{@link #seedTenant}, {@link #seedUser}, {@link #seedDomain},
 *       {@link #seedDataset} — single-row primitives.</li>
 *   <li>{@link #seedPipelineWithVersion} — Pipeline + active PipelineVersion in one call.</li>
 *   <li>{@link #seedSubPipelineInstance}, {@link #seedPortWiring} — composition-layer rows
 *       used by {@link NonBlueprintCompositionFixture} and integration tests.</li>
 *   <li>{@link #seedFullPipelineContext} — end-to-end seed returning a {@link Context} so a
 *       test can grab one object and drive cross-module flows.</li>
 * </ul>
 *
 * <p>Tenant ids are deterministic per process to keep test logs readable, but each call to
 * {@link #seedTenant} or {@link #seedFullPipelineContext} returns a fresh suffix so two
 * seeds in the same test do not collide.
 */
public final class SeedFixtures {

    private static final AtomicLong COUNTER = new AtomicLong(0);
    // Per-JVM prefix so two integration runs against the same shared Postgres do not
    // collide on UNIQUE constraints (e.g. tenants.name / tenants.slug). The fast PR
    // lane (H2 + create-drop) starts each suite with an empty DB so the prefix is
    // harmless; the postgres-it lane reuses the database across runs and benefits
    // from the disambiguator.
    private static final String JVM_PREFIX = Long.toString(System.nanoTime(), 36);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final DomainRepository domainRepository;
    private final SystemOfRecordRepository sorRepository;
    private final ConnectorDefinitionRepository connectorDefinitionRepository;
    private final ConnectorInstanceRepository connectorInstanceRepository;
    private final DatasetRepository datasetRepository;
    private final BlueprintRepository blueprintRepository;
    private final PipelineRepository pipelineRepository;
    private final PipelineVersionRepository pipelineVersionRepository;
    private final SubPipelineInstanceRepository subPipelineInstanceRepository;
    private final PortWiringRepository portWiringRepository;

    public SeedFixtures(TenantRepository tenantRepository,
                        UserRepository userRepository,
                        DomainRepository domainRepository,
                        SystemOfRecordRepository sorRepository,
                        ConnectorDefinitionRepository connectorDefinitionRepository,
                        ConnectorInstanceRepository connectorInstanceRepository,
                        DatasetRepository datasetRepository,
                        BlueprintRepository blueprintRepository,
                        PipelineRepository pipelineRepository,
                        PipelineVersionRepository pipelineVersionRepository,
                        SubPipelineInstanceRepository subPipelineInstanceRepository,
                        PortWiringRepository portWiringRepository) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.domainRepository = domainRepository;
        this.sorRepository = sorRepository;
        this.connectorDefinitionRepository = connectorDefinitionRepository;
        this.connectorInstanceRepository = connectorInstanceRepository;
        this.datasetRepository = datasetRepository;
        this.blueprintRepository = blueprintRepository;
        this.pipelineRepository = pipelineRepository;
        this.pipelineVersionRepository = pipelineVersionRepository;
        this.subPipelineInstanceRepository = subPipelineInstanceRepository;
        this.portWiringRepository = portWiringRepository;
    }

    /**
     * Returns a unique-per-process suffix used to keep seeded ids/slugs human-readable but
     * collision-free across multiple seeds within the same JVM AND across multiple integration
     * runs that share a single Postgres database.
     */
    public static String nextSuffix() {
        return JVM_PREFIX + "-" + COUNTER.incrementAndGet();
    }

    /** Creates and persists a Tenant with deterministic name/slug. */
    public Tenant seedTenant() {
        return seedTenant("test-" + nextSuffix());
    }

    /** Creates and persists a Tenant using the provided slug as both name and slug seed. */
    public Tenant seedTenant(String slug) {
        Tenant tenant = new Tenant();
        tenant.setName("Tenant " + slug);
        tenant.setSlug(slug.toLowerCase(Locale.ROOT));
        // V87 enforces ck_tenants_origin CHECK (origin IN ('bootstrap','api')) on Postgres.
        // The fast PR lane (H2) does not enforce check constraints by default and historically
        // accepted "test", but the integration PR lane (postgres-it) rejects it. Use
        // "bootstrap" so the same seed works on both lanes.
        tenant.setOrigin("bootstrap");
        tenant.setStatus("active");
        return tenantRepository.save(tenant);
    }

    /** Seeds a DATA_ENGINEER user in the given tenant. */
    public PulseUser seedUser(String tenantId) {
        return seedUser(tenantId, UserRole.DATA_ENGINEER);
    }

    /** Seeds a user with the provided role. */
    public PulseUser seedUser(String tenantId, UserRole role) {
        PulseUser user = new PulseUser();
        String suffix = nextSuffix();
        user.setEmail("user-" + suffix + "@test.pulse");
        user.setDisplayName("Test User " + suffix);
        user.setRole(role);
        user.setTenantId(tenantId);
        user.setActive(true);
        user.setAuthProvider("local");
        return userRepository.save(user);
    }

    /** Seeds a domain in the tenant. */
    public Domain seedDomain(String tenantId) {
        return seedDomain(tenantId, "domain-" + nextSuffix());
    }

    /** Seeds a domain in the tenant with the provided slug. */
    public Domain seedDomain(String tenantId, String slug) {
        Domain d = new Domain();
        d.setTenantId(tenantId);
        d.setName("Domain " + slug);
        d.setSlug(slug.toLowerCase(Locale.ROOT));
        d.setDescription("Seeded by SeedFixtures");
        return domainRepository.save(d);
    }

    /**
     * Seeds a dataset in the tenant, including the SOR + connector definition + connector
     * instance backbone the dataset needs to be queryable through the SOR repository.
     */
    public Dataset seedDataset(String tenantId, String domainId) {
        // V10 enforces systems_of_record.owner_id REFERENCES users(id) on Postgres. The fast
        // PR lane (H2 + create-drop) does not enforce FKs by default and historically accepted
        // a sentinel "seed-fixtures" id, but the integration PR lane (postgres-it) rejects it.
        // Resolve a real user in the tenant — seeding one if none exists — so the same seed
        // works on both lanes.
        PulseUser owner = userRepository.findAll().stream()
                .filter(u -> tenantId.equals(u.getTenantId()))
                .findFirst()
                .orElseGet(() -> seedUser(tenantId));

        SystemOfRecord sor = new SystemOfRecord();
        sor.setTenantId(tenantId);
        sor.setName("SOR " + nextSuffix());
        sor.setDescription("Seeded SOR");
        sor.setDomainId(domainId);
        sor.setOwnerId(owner.getId());
        sor.setMetadata(Map.of("registry_type", "SOURCE"));
        sor = sorRepository.save(sor);

        ConnectorDefinition def = new ConnectorDefinition();
        def.setName("Seed Connector " + nextSuffix());
        def.setConnectorType(ConnectorType.SOURCE);
        def.setDockerRepository("pulse/test-source");
        def.setDockerImageTag("0.0.0-test");
        def.setConnectionSpec(Map.of());
        def.setSupportedModes(List.of("full_refresh"));
        def.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        def = connectorDefinitionRepository.save(def);

        ConnectorInstance inst = new ConnectorInstance();
        inst.setSorId(sor.getId());
        inst.setConnectorDefinitionId(def.getId());
        inst.setName("Seed Connector Instance " + nextSuffix());
        inst.setConfigTemplate(Map.of("file_format", "csv"));
        inst.setEnabled(true);
        inst = connectorInstanceRepository.save(inst);

        Dataset ds = new Dataset();
        ds.setTenantId(tenantId);
        ds.setSorId(sor.getId());
        ds.setConnectorInstanceId(inst.getId());
        String suffix = nextSuffix();
        ds.setName("seed_dataset_" + suffix);
        // qualifiedName is unique
        ds.setQualifiedName("seed.tenant_" + tenantId + ".ds_" + suffix);
        ds.setDescription("Seeded by SeedFixtures");
        ds.setStatus("active");
        return datasetRepository.save(ds);
    }

    /** Seeds a Pipeline with one ENGINEERING-stage version, returning both. */
    public PipelineWithVersion seedPipelineWithVersion(String tenantId, String domainId, String domainName) {
        Pipeline pipeline = new Pipeline();
        pipeline.setTenantId(tenantId);
        pipeline.setDomainId(domainId);
        pipeline.setDomainName(domainName);
        pipeline.setName("Seed Pipeline " + nextSuffix());
        pipeline.setDescription("Seeded by SeedFixtures");
        pipeline.setCreatedBy("seed-fixtures");
        pipeline = pipelineRepository.save(pipeline);

        PipelineVersion version = new PipelineVersion();
        version.setPipelineId(pipeline.getId());
        version.setRevision(1);
        version.setLifecycleStage(PipelineStage.ENGINEERING);
        version.setCreatedBy("seed-fixtures");
        version = pipelineVersionRepository.save(version);

        pipeline.setActiveVersionId(version.getId());
        pipeline = pipelineRepository.save(pipeline);

        return new PipelineWithVersion(pipeline, version);
    }

    /**
     * Seeds a SubPipelineInstance row with the given blueprintKey + params. The caller supplies
     * the blueprint id explicitly because some test profiles seed the catalog from migration
     * (Postgres) and others do not (H2 + create-drop) — see {@link NonBlueprintCompositionFixture}
     * which seeds blueprint rows of its own when needed.
     */
    public SubPipelineInstance seedSubPipelineInstance(String pipelineId,
                                                       String versionId,
                                                       String blueprintId,
                                                       String blueprintKey,
                                                       String name,
                                                       int executionOrder,
                                                       Map<String, Object> params) {
        SubPipelineInstance inst = new SubPipelineInstance();
        inst.setPipelineId(pipelineId);
        inst.setVersionId(versionId);
        inst.setBlueprintId(blueprintId);
        inst.setBlueprintKey(blueprintKey);
        inst.setBlueprintVersion("1.0.0");
        inst.setName(name);
        inst.setExecutionOrder(executionOrder);
        inst.setParams(params == null ? Map.of() : params);
        inst.setSchemaStatus("unknown");
        inst.setStorageBackend("DPC");
        return subPipelineInstanceRepository.save(inst);
    }

    /** Seeds a single PortWiring row connecting two SubPipelineInstance rows. */
    public PortWiring seedPortWiring(String versionId,
                                     String sourceInstanceId,
                                     String sourcePortName,
                                     String targetInstanceId,
                                     String targetPortName) {
        PortWiring w = new PortWiring();
        w.setVersionId(versionId);
        w.setSourceInstanceId(sourceInstanceId);
        w.setSourcePortName(sourcePortName);
        w.setTargetInstanceId(targetInstanceId);
        w.setTargetPortName(targetPortName);
        return portWiringRepository.save(w);
    }

    /**
     * Seeds the canonical full pipeline context: tenant, user, domain, dataset, pipeline, version.
     * Returns a {@link Context} the caller can pass around.
     *
     * <p>Composition (sub-pipeline instances + port wirings) is left empty so callers can layer
     * on either {@link NonBlueprintCompositionFixture} or a blueprint-specific factory. This
     * matches the Wave 0 boundary: SeedFixtures owns identity/data, NonBlueprintCompositionFixture
     * owns the composition layer.
     */
    public Context seedFullPipelineContext() {
        Tenant tenant = seedTenant();
        PulseUser user = seedUser(tenant.getId());
        Domain domain = seedDomain(tenant.getId());
        Dataset dataset = seedDataset(tenant.getId(), domain.getId());
        PipelineWithVersion pv = seedPipelineWithVersion(tenant.getId(), domain.getId(), domain.getName());
        return new Context(tenant, user, domain, dataset, pv.pipeline(), pv.version());
    }

    /**
     * Convenience helper to seed a minimal blueprint row when the test profile does not run
     * the V7 catalog migration (e.g., H2 + create-drop with Flyway disabled).
     *
     * <p>Idempotent on {@code blueprint_key}. The {@code blueprints.blueprint_key} column is
     * {@code unique}, but unlike tenants/users/etc. (which carry a {@link #nextSuffix()} guard)
     * blueprints are seeded by a well-known literal key because the LLM tool surface and the
     * contract assertions reference that exact name. Two test methods sharing one Spring context
     * (no per-method DB reset) therefore both ask for, e.g., {@code BronzeToSilverCleaning}.
     * A blind {@code save} of a second row with the same key violates the unique constraint and
     * surfaces as a duplicate-key seed conflict. We instead reuse the existing row (updating its
     * shape to match the requested category/ports) so repeated seeds of the same key are safe
     * across methods that share a JVM/DB. See ChatControllerSseContractTest (toolWithEmptyArgs +
     * vertexThoughtSignature both seed BronzeToSilverCleaning).
     */
    public Blueprint seedBlueprint(String blueprintKey, BlueprintCategory category, List<Map<String, Object>> inputPorts, List<Map<String, Object>> outputPorts) {
        Blueprint bp = blueprintRepository.findByBlueprintKey(blueprintKey).orElseGet(Blueprint::new);
        bp.setBlueprintKey(blueprintKey);
        bp.setName(blueprintKey);
        bp.setDescription("Seeded by SeedFixtures: " + blueprintKey);
        bp.setCategory(category);
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(inputPorts == null ? List.of() : inputPorts);
        bp.setOutputPorts(outputPorts == null ? List.of() : outputPorts);
        bp.setRuntimeRequirements(Map.of());
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        bp.setSupportsReuse(false);
        bp.setStatus("active");
        bp.setValidLayers(List.of("bronze"));
        return blueprintRepository.save(bp);
    }

    // ---- result types ----

    /** Pipeline + active version returned together so callers can wire composition. */
    public record PipelineWithVersion(Pipeline pipeline, PipelineVersion version) { }

    /**
     * Context object returned by {@link #seedFullPipelineContext}. Intentionally exposes the
     * domain entities (not just ids) so downstream tests can assert on names/slugs/roles
     * without re-loading rows.
     */
    public record Context(Tenant tenant,
                          PulseUser user,
                          Domain domain,
                          Dataset dataset,
                          Pipeline pipeline,
                          PipelineVersion version) {
        public String tenantId() { return tenant.getId(); }
        public String userId() { return user.getId(); }
        public String domainId() { return domain.getId(); }
        public String datasetId() { return dataset.getId(); }
        public String pipelineId() { return pipeline.getId(); }
        public String versionId() { return version.getId(); }
    }

    /**
     * Generates a stable but unique-enough id to use when callers want to inject their own
     * id rather than rely on JPA-generated ULIDs.
     */
    public static String fixtureId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
