package com.pulse.git.service;

import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.service.TenantService;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.config.TenantConfig.TenantDefinition;
import com.pulse.git.identity.UserGitIdentity;
import com.pulse.git.identity.UserGitIdentityService;
import com.pulse.git.model.GitRepo;
import com.pulse.git.model.TenantRepoScaffoldItem;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.repository.TenantRepoScaffoldItemRepository;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.DomainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Creates and maintains the on-disk folder structure inside the tenant-scoped
 * git repo. The layout matches Agent A's expected codegen paths (§10).
 *
 * Directory creation is idempotent; commit-if-changed keeps reapply no-ops
 * when nothing new was created.
 */
@Service
public class RepoScaffoldService {

    private static final Logger log = LoggerFactory.getLogger(RepoScaffoldService.class);
    private static final String TENANT_SCOPE = "TENANT";
    private static final String REMOTE_TYPE = "REMOTE";
    private static final String ITEM_TOP_LEVEL = "TOP_LEVEL";
    private static final String ITEM_DBT_PROJECT = "DBT_PROJECT";
    private static final String ITEM_DOMAIN = "DOMAIN";
    private static final String STATUS_SCAFFOLDED = "SCAFFOLDED";
    private static final String STATUS_MISSING = "MISSING";

    private final GitRepoRepository gitRepoRepository;
    private final DomainRepository domainRepository;
    private final TenantService tenantService;
    private final LocalGitService localGitService;
    private final TenantRepoScaffoldItemRepository scaffoldItemRepository;
    private final UserGitIdentityService identityService;
    private final RemoteGitService remoteGitService;

    public RepoScaffoldService(GitRepoRepository gitRepoRepository,
                               DomainRepository domainRepository,
                               TenantService tenantService,
                               LocalGitService localGitService,
                               TenantRepoScaffoldItemRepository scaffoldItemRepository,
                               UserGitIdentityService identityService,
                               RemoteGitService remoteGitService) {
        this.gitRepoRepository = gitRepoRepository;
        this.domainRepository = domainRepository;
        this.tenantService = tenantService;
        this.localGitService = localGitService;
        this.scaffoldItemRepository = scaffoldItemRepository;
        this.identityService = identityService;
        this.remoteGitService = remoteGitService;
    }

    /**
     * Writes the top-level dbt project scaffolding, ensures a directory exists
     * for each domain currently registered under the tenant, and commits if
     * anything changed.
     */
    public void scaffold(String tenantId) {
        GitRepo repo = requireTenantRepo(tenantId);
        TenantDefinition tenant = tenantService.getTenant(tenantId);
        Path root = Path.of(repo.getLocalPath());
        try {
            Files.createDirectories(root);
            writeTopLevel(root, tenant);
            writeDbtProject(root, tenant);
            for (Domain domain : domainRepository.findByTenantIdOrderByNameAsc(tenantId)) {
                String domainSlug = slugFor(domain);
                ensurePipelineParent(root, domainSlug);
                writeDomainDirectories(root, domainSlug);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scaffold tenant repo at " + root, e);
        }
        // commit-if-changed pattern: re-scaffold for an already-onboarded tenant
        // produces an empty no-op commit when the rewritten PULSE-managed files
        // (dbt_project.yml, profiles.yml, packages.yml, macros/) are byte-identical
        // to what's on disk; otherwise the commit captures the refresh.
        localGitService.commitAll(repo.getLocalPath(),
                "pulse: scaffold tenant repository (refresh dbt project + profiles + macros)");
    }

    public ScaffoldResult scaffold(String tenantId, ScaffoldRequest request, CallerContext caller) {
        ScaffoldRequest effectiveRequest = request == null ? ScaffoldRequest.all() : request;
        GitRepo repo = requireTenantRepo(tenantId);
        TenantDefinition tenant = tenantService.getTenant(tenantId);
        UserGitIdentity identity = null;
        if (REMOTE_TYPE.equals(repo.getRepoType())) {
            identity = identityService.requireValidIdentity(caller);
            remoteGitService.pullFromRemote(repo, identity);
        }

        Path root = Path.of(repo.getLocalPath());
        String branch = branchName(repo);
        List<Domain> domains = selectedDomains(tenantId, repo, branch, effectiveRequest);
        Instant now = Instant.now();
        try {
            Files.createDirectories(root);
            if (effectiveRequest.shouldRefreshTopLevel()) {
                writeTopLevel(root, tenant);
                writeDbtProject(root, tenant);
                markNonDomainItem(repo, branch, ITEM_TOP_LEVEL, caller, now, null);
                markNonDomainItem(repo, branch, ITEM_DBT_PROJECT, caller, now, null);
            }
            for (Domain domain : domains) {
                String domainSlug = slugFor(domain);
                ensurePipelineParent(root, domainSlug);
                writeDomainDirectories(root, domainSlug);
                markDomainItem(repo, branch, domain, domainSlug, caller, now, null);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scaffold tenant repo at " + root, e);
        }

        String commitMessage = "pulse: scaffold tenant repository";
        if (identity != null) {
            localGitService.commitAsUser(repo.getLocalPath(), commitMessage,
                    identity.getAuthorName(), identity.getAuthorEmail());
        } else {
            localGitService.commitAll(repo.getLocalPath(), commitMessage);
        }
        String commitSha = localGitService.getHeadSha(repo.getLocalPath());
        stampCommit(repo, branch, domains, commitSha);

        String pushStatus = "LOCAL_ONLY";
        if (identity != null) {
            try {
                remoteGitService.pushToRemote(repo, identity);
                pushStatus = "PUSHED";
            } catch (GitAuthenticationException e) {
                pushStatus = "PENDING_PUSH";
                log.warn("Scaffold push skipped for tenant {}: {}", tenantId, e.getMessage());
            }
        }

        List<ScaffoldDomainResult> domainResults = domains.stream()
                .map(domain -> new ScaffoldDomainResult(
                        domain.getId(),
                        slugFor(domain),
                        STATUS_SCAFFOLDED,
                        null))
                .toList();
        return new ScaffoldResult(repo.getId(), commitSha, pushStatus, domainResults);
    }

    /**
     * Creates {@code {domain_slug}/pipelines/{pipeline_slug}/...} subtree and
     * commits.
     */
    public void ensurePipelineDirectory(String tenantId, String domainSlug, String pipelineSlug) {
        GitRepo repo = requireTenantRepo(tenantId);
        Path root = Path.of(repo.getLocalPath());
        Path pipelineRoot = root.resolve(domainSlug).resolve("pipelines").resolve(pipelineSlug);
        try {
            Files.createDirectories(pipelineRoot.resolve("dags"));
            Files.createDirectories(pipelineRoot.resolve("jobs").resolve("ingestion"));
            Files.createDirectories(pipelineRoot.resolve("jobs").resolve("sinks"));
            Files.createDirectories(pipelineRoot.resolve("gx").resolve("checkpoints"));
            Files.createDirectories(pipelineRoot.resolve("config"));
            writeGitKeep(pipelineRoot.resolve("dags"));
            writeGitKeep(pipelineRoot.resolve("jobs").resolve("ingestion"));
            writeGitKeep(pipelineRoot.resolve("jobs").resolve("sinks"));
            writeGitKeep(pipelineRoot.resolve("gx").resolve("checkpoints"));
            writeGitKeep(pipelineRoot.resolve("config"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to ensure pipeline directory " + pipelineRoot, e);
        }
        localGitService.commitAll(repo.getLocalPath(),
                "pulse: ensure pipeline directory " + domainSlug + "/" + pipelineSlug);
    }

    /**
     * Creates the dbt domain-shaped directories. Does NOT create
     * {@code dbt_project/models/staging/{domain_slug}/} — staging is
     * source-system-based and gets created by Agent A on demand.
     */
    public void ensureDomainDirectories(String tenantId, String domainSlug) {
        GitRepo repo = requireTenantRepo(tenantId);
        Path root = Path.of(repo.getLocalPath());
        try {
            ensurePipelineParent(root, domainSlug);
            writeDomainDirectories(root, domainSlug);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to ensure domain directories for " + domainSlug, e);
        }
        localGitService.commitAll(repo.getLocalPath(),
                "pulse: ensure domain directories " + domainSlug);
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private GitRepo requireTenantRepo(String tenantId) {
        return gitRepoRepository.findByTenantIdAndScope(tenantId, TENANT_SCOPE)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant git repo", tenantId));
    }

    public static List<String> topLevelPaths() {
        return List.of(
                ".gitignore",
                "README.md",
                "dbt_project/dbt_project.yml",
                "dbt_project/profiles.yml",
                "dbt_project/packages.yml",
                "dbt_project/macros/audit_columns.sql",
                "dbt_project/macros/safe_cast.sql",
                "dbt_project/macros/pulse_delta_table.sql");
    }

    public static List<String> domainPaths(String domainSlug) {
        return List.of(
                domainSlug + "/pipelines/.gitkeep",
                "dbt_project/models/intermediate/" + domainSlug + "/.gitkeep",
                "dbt_project/models/marts/" + domainSlug + "/.gitkeep",
                "dbt_project/snapshots/" + domainSlug + "/.gitkeep");
    }

    public static String slugFor(Domain domain) {
        if (domain.getSlug() != null && !domain.getSlug().isBlank()) {
            return domain.getSlug();
        }
        return safeIdentifier(domain.getName() != null ? domain.getName() : domain.getId());
    }

    private List<Domain> selectedDomains(String tenantId,
                                         GitRepo repo,
                                         String branch,
                                         ScaffoldRequest request) {
        List<Domain> all = domainRepository.findByTenantIdOrderByNameAsc(tenantId);
        ScaffoldMode mode = request.resolvedMode();
        if (mode == ScaffoldMode.SELECTED) {
            Set<String> selected = new HashSet<>(request.domainIds() == null ? List.of() : request.domainIds());
            return all.stream()
                    .filter(domain -> selected.contains(domain.getId()))
                    .toList();
        }
        if (mode == ScaffoldMode.MISSING_ONLY && repo.getId() != null) {
            return all.stream()
                    .filter(domain -> scaffoldItemRepository
                            .findByGitRepoIdAndBranchNameAndDomainId(repo.getId(), branch, domain.getId())
                            .map(item -> !STATUS_SCAFFOLDED.equals(item.getStatus()))
                            .orElse(true))
                    .toList();
        }
        return all;
    }

    private void markNonDomainItem(GitRepo repo,
                                   String branch,
                                   String itemType,
                                   CallerContext caller,
                                   Instant now,
                                   String lastError) {
        if (repo.getId() == null) return;
        TenantRepoScaffoldItem item = scaffoldItemRepository
                .findByGitRepoIdAndBranchNameAndItemTypeAndDomainIdIsNull(repo.getId(), branch, itemType)
                .orElseGet(TenantRepoScaffoldItem::new);
        fillCommon(item, repo, branch, itemType, null, null, caller, now, lastError);
        scaffoldItemRepository.save(item);
    }

    private void markDomainItem(GitRepo repo,
                                String branch,
                                Domain domain,
                                String domainSlug,
                                CallerContext caller,
                                Instant now,
                                String lastError) {
        if (repo.getId() == null) return;
        TenantRepoScaffoldItem item = scaffoldItemRepository
                .findByGitRepoIdAndBranchNameAndDomainId(repo.getId(), branch, domain.getId())
                .orElseGet(TenantRepoScaffoldItem::new);
        fillCommon(item, repo, branch, ITEM_DOMAIN, domain.getId(), domainSlug, caller, now, lastError);
        scaffoldItemRepository.save(item);
    }

    private void fillCommon(TenantRepoScaffoldItem item,
                            GitRepo repo,
                            String branch,
                            String itemType,
                            String domainId,
                            String domainSlug,
                            CallerContext caller,
                            Instant now,
                            String lastError) {
        item.setTenantId(repo.getTenantId());
        item.setGitRepoId(repo.getId());
        item.setBranchName(branch);
        item.setItemType(itemType);
        item.setDomainId(domainId);
        item.setDomainSlug(domainSlug);
        item.setStatus(lastError == null ? STATUS_SCAFFOLDED : "ERROR");
        item.setLastScaffoldedAt(now);
        item.setLastScaffoldedByUserId(caller == null ? null : caller.userId());
        item.setLastError(lastError);
    }

    private void stampCommit(GitRepo repo, String branch, List<Domain> domains, String commitSha) {
        if (repo.getId() == null) return;
        scaffoldItemRepository
                .findByGitRepoIdAndBranchNameOrderByItemTypeAscDomainSlugAsc(repo.getId(), branch)
                .stream()
                .filter(item -> ITEM_TOP_LEVEL.equals(item.getItemType())
                        || ITEM_DBT_PROJECT.equals(item.getItemType())
                        || domains.stream().map(Domain::getId).anyMatch(id -> id.equals(item.getDomainId())))
                .sorted(Comparator.comparing(TenantRepoScaffoldItem::getItemType))
                .forEach(item -> {
                    item.setLastCommitSha(commitSha);
                    scaffoldItemRepository.save(item);
                });
    }

    private String branchName(GitRepo repo) {
        if (repo.getCurrentBranch() != null && !repo.getCurrentBranch().isBlank()) {
            return repo.getCurrentBranch();
        }
        if (repo.getDefaultBranch() != null && !repo.getDefaultBranch().isBlank()) {
            return repo.getDefaultBranch();
        }
        return "main";
    }

    private void writeTopLevel(Path root, TenantDefinition tenant) throws IOException {
        Path gitignore = root.resolve(".gitignore");
        if (!Files.exists(gitignore)) {
            Files.writeString(gitignore, String.join("\n",
                    "# PULSE managed repository",
                    "__pycache__/",
                    "*.pyc",
                    "*.pyo",
                    ".env",
                    "*.secret",
                    "target/",
                    "dbt_packages/",
                    ".DS_Store",
                    ""));
        }
        Path readme = root.resolve("README.md");
        if (!Files.exists(readme)) {
            Files.writeString(readme,
                    "# " + tenant.getName() + "\n\n"
                            + "PULSE managed repository for tenant `" + tenant.getId() + "`.\n");
        }
    }

    private void writeDbtProject(Path root, TenantDefinition tenant) throws IOException {
        Path dbtRoot = root.resolve("dbt_project");
        // Canonical dbt-labs project layout (per "How we structure our dbt projects"):
        //   models/{staging,intermediate,marts}, snapshots/, macros/, seeds/, tests/, analyses/
        //   plus selectors/ for selector YAMLs.
        Files.createDirectories(dbtRoot.resolve("models").resolve("staging"));
        Files.createDirectories(dbtRoot.resolve("models").resolve("intermediate"));
        Files.createDirectories(dbtRoot.resolve("models").resolve("marts"));
        Files.createDirectories(dbtRoot.resolve("snapshots"));
        Files.createDirectories(dbtRoot.resolve("selectors"));
        Files.createDirectories(dbtRoot.resolve("macros"));
        Files.createDirectories(dbtRoot.resolve("seeds"));
        Files.createDirectories(dbtRoot.resolve("tests"));
        Files.createDirectories(dbtRoot.resolve("analyses"));
        writeGitKeep(dbtRoot.resolve("models").resolve("staging"));
        writeGitKeep(dbtRoot.resolve("models").resolve("intermediate"));
        writeGitKeep(dbtRoot.resolve("models").resolve("marts"));
        writeGitKeep(dbtRoot.resolve("snapshots"));
        writeGitKeep(dbtRoot.resolve("selectors"));
        writeGitKeep(dbtRoot.resolve("seeds"));
        writeGitKeep(dbtRoot.resolve("tests"));
        writeGitKeep(dbtRoot.resolve("analyses"));

        // dbt_project.yml is PULSE-managed — rewrite on every scaffold so re-scaffolding
        // existing tenants picks up updated materialization defaults (e.g. staging=table
        // per the view-is-evil ADR for Spark/Iceberg).
        // Default target is `local` (in-process SparkSession against the docker-compose
        // spark-master).  Override with DBT_TARGET=dev in deploy contexts that connect
        // to an external Hive Thrift Spark gateway.
        String projectName = safeIdentifier(tenant.getSlug() != null ? tenant.getSlug() : tenant.getId());
        Path dbtProjectYml = dbtRoot.resolve("dbt_project.yml");
        Files.writeString(dbtProjectYml, String.join("\n",
                "# PULSE-managed — rewritten on every scaffold. Edits to this file are reverted.",
                "# Materialization defaults follow ADR-001 (silver-materializes-as-table on",
                "# dbt-spark/Iceberg substrate; view-default would force re-scans of bronze).",
                "name: '" + projectName + "'",
                "version: '0.1.0'",
                "config-version: 2",
                "profile: '" + projectName + "'",
                "require-dbt-version: \">=1.7.0,<2.0.0\"",
                "model-paths: [\"models\"]",
                "seed-paths: [\"seeds\"]",
                "snapshot-paths: [\"snapshots\"]",
                "test-paths: [\"tests\"]",
                "macro-paths: [\"macros\"]",
                "analysis-paths: [\"analyses\"]",
                "target-path: \"target\"",
                "clean-targets:",
                "  - target",
                "  - dbt_packages",
                "",
                "quoting:",
                "  database: false",
                "  schema: false",
                "  identifier: false",
                "",
                "models:",
                "  " + projectName + ":",
                "    +persist_docs:",
                "      relation: true",
                "      columns: true",
                "    staging:",
                "      +materialized: table",
                "      +tags: ['staging', 'silver']",
                "    intermediate:",
                "      +materialized: table",
                "      +tags: ['intermediate', 'silver']",
                "    marts:",
                "      +materialized: table",
                "      +tags: ['marts', 'gold']",
                "",
                "snapshots:",
                "  " + projectName + ":",
                "    +target_schema: snapshots",
                ""));

        // packages.yml — PULSE-managed
        Path packagesYml = dbtRoot.resolve("packages.yml");
        Files.writeString(packagesYml, String.join("\n",
                "# PULSE-managed — rewritten on every scaffold.",
                "packages:",
                "  - package: dbt-labs/dbt_utils",
                "    version: [\">=1.1.0\", \"<2.0.0\"]",
                ""));

        // profiles.yml — PULSE-managed; rewritten on every scaffold.
        //
        // Two targets:
        //   - `local`: in-process SparkSession (dbt-spark[session]) dispatching to the
        //     docker-compose spark-master at spark://spark-master:7077. Used when dbt
        //     runs from the local airflow container or any host that has SPARK_MASTER_URL
        //     pointing at the compose cluster.
        //   - `dev`: Hive Thrift connect to an external Spark gateway provisioned by
        //     the platform team per #72 (tenant compute config). Selected via
        //     DBT_TARGET=dev in gcp-deploy.sh / Cloud Run env.
        //
        // Default target: `local`.  All env-var defaults match the docker-compose values
        // wired in the airflow service so `dbt run` from inside the airflow container
        // works zero-config.
        Path profilesYml = dbtRoot.resolve("profiles.yml");
        Files.writeString(profilesYml, String.join("\n",
                "# PULSE-managed — rewritten on every scaffold.",
                "# Local target: dbt-spark[session] in-process; dispatches to docker-compose spark-master",
                "#   via SPARK_MASTER_URL env var (set in airflow container).",
                "# Dev target: Hive Thrift to external Spark gateway; configured by #72 tenant compute.",
                projectName + ":",
                "  target: \"{{ env_var('DBT_TARGET', 'local') }}\"",
                "  outputs:",
                "    local:",
                "      type: spark",
                "      method: session",
                "      host: NA   # unused for session method; required by adapter schema",
                "      schema: \"{{ env_var('DBT_SCHEMA', 'pulse_dev') }}\"",
                "      threads: \"{{ env_var('DBT_THREADS', '2') | int }}\"",
                "      server_side_parameters:",
                "        spark.master: \"{{ env_var('SPARK_MASTER_URL', 'local[*]') }}\"",
                "        spark.sql.extensions: io.delta.sql.DeltaSparkSessionExtension",
                "        spark.sql.catalog.spark_catalog: org.apache.spark.sql.delta.catalog.DeltaCatalog",
                "        spark.jars.packages: org.apache.hadoop:hadoop-aws:3.3.4,com.amazonaws:aws-java-sdk-bundle:1.12.262,io.delta:delta-spark_2.12:3.3.2",
                "        spark.hadoop.fs.s3a.impl: org.apache.hadoop.fs.s3a.S3AFileSystem",
                "        spark.hadoop.fs.s3a.endpoint: \"{{ env_var('MINIO_ENDPOINT', 'http://minio:9000') }}\"",
                "        spark.hadoop.fs.s3a.access.key: \"{{ env_var('AWS_ACCESS_KEY_ID', env_var('MINIO_ACCESS_KEY', 'minioadmin')) }}\"",
                "        spark.hadoop.fs.s3a.secret.key: \"{{ env_var('AWS_SECRET_ACCESS_KEY', env_var('MINIO_SECRET_KEY', 'minioadmin')) }}\"",
                "        spark.hadoop.fs.s3a.path.style.access: 'true'",
                "        spark.hadoop.fs.s3a.connection.ssl.enabled: 'false'",
                "    dev:",
                "      type: spark",
                "      method: thrift",
                "      schema: \"{{ env_var('DBT_SPARK_SCHEMA', 'silver_dev') }}\"",
                "      host: \"{{ env_var('DBT_SPARK_HOST', 'localhost') }}\"",
                "      port: \"{{ env_var('DBT_SPARK_PORT', '10000') | int }}\"",
                "      user: \"{{ env_var('DBT_SPARK_USER', '') }}\"",
                "      password: \"{{ env_var('DBT_SPARK_PASSWORD', '') }}\"",
                "      threads: \"{{ env_var('DBT_SPARK_THREADS', '4') | int }}\"",
                "      connect_timeout: 60",
                "      connect_retries: 3",
                ""));

        // PULSE-managed macros — rewritten on every scaffold.
        writeMacro(dbtRoot, "audit_columns.sql", String.join("\n",
                "{# PULSE-managed. Adds standard audit columns to a SELECT. Use as: #}",
                "{# SELECT {{ audit_columns() }}, * FROM ... #}",
                "{% macro audit_columns() %}",
                "    current_timestamp() AS _pulse_processed_at,",
                "    '{{ invocation_id }}' AS _pulse_run_id",
                "{% endmacro %}",
                ""));
        writeMacro(dbtRoot, "safe_cast.sql", String.join("\n",
                "{# PULSE-managed. Cast that returns NULL on failure instead of erroring. #}",
                "{# Spark-native equivalent of try_cast. #}",
                "{% macro safe_cast(field, target_type) %}",
                "    try_cast({{ field }} AS {{ target_type }})",
                "{% endmacro %}",
                ""));
        writeMacro(dbtRoot, "pulse_delta_table.sql", String.join("\n",
                "{# PULSE-managed. Delta table materialization for Spark local and deployed runtimes. #}",
                "{% materialization pulse_delta_table, adapter='spark' %}",
                "    {%- set identifier = model['alias'] -%}",
                "    {%- set location_root = config.get('location_root') -%}",
                "    {%- if location_root is none or location_root | trim == '' -%}",
                "        {{ exceptions.raise_compiler_error('pulse_delta_table requires config.location_root') }}",
                "    {%- endif -%}",
                "    {%- set target_location = location_root.rstrip('/') ~ '/' ~ identifier -%}",
                "    {%- set target_relation = api.Relation.create(",
                "        identifier=identifier,",
                "        schema=schema,",
                "        database=database,",
                "        type='table'",
                "    ) -%}",
                "",
                "    {{ run_hooks(pre_hooks) }}",
                "",
                "    {%- call statement('drop_relation') -%}",
                "        DROP TABLE IF EXISTS {{ target_relation }}",
                "    {%- endcall -%}",
                "",
                "    {%- call statement('main') -%}",
                "        CREATE TABLE {{ target_relation }}",
                "        USING DELTA",
                "        LOCATION '{{ target_location }}'",
                "        AS",
                "        {{ compiled_code }}",
                "    {%- endcall -%}",
                "",
                "    {{ run_hooks(post_hooks) }}",
                "    {{ return({'relations': [target_relation]}) }}",
                "{% endmaterialization %}",
                ""));
    }

    private void writeMacro(Path dbtRoot, String fileName, String content) throws IOException {
        Path macroPath = dbtRoot.resolve("macros").resolve(fileName);
        Files.writeString(macroPath, content);
    }

    private void writeDomainDirectories(Path root, String domainSlug) throws IOException {
        Path dbtRoot = root.resolve("dbt_project");
        Path intermediate = dbtRoot.resolve("models").resolve("intermediate").resolve(domainSlug);
        Path marts = dbtRoot.resolve("models").resolve("marts").resolve(domainSlug);
        Path snapshots = dbtRoot.resolve("snapshots").resolve(domainSlug);
        Files.createDirectories(intermediate);
        Files.createDirectories(marts);
        Files.createDirectories(snapshots);
        writeGitKeep(intermediate);
        writeGitKeep(marts);
        writeGitKeep(snapshots);
    }

    private void ensurePipelineParent(Path root, String domainSlug) throws IOException {
        Path pipelinesDir = root.resolve(domainSlug).resolve("pipelines");
        Files.createDirectories(pipelinesDir);
        writeGitKeep(pipelinesDir);
    }

    private void writeGitKeep(Path dir) throws IOException {
        Path keep = dir.resolve(".gitkeep");
        if (!Files.exists(keep)) {
            Files.writeString(keep, "");
        }
    }

    static String safeIdentifier(String raw) {
        if (raw == null || raw.isBlank()) return "tenant";
        String lower = raw.toLowerCase(Locale.ROOT);
        String cleaned = lower.replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (cleaned.isEmpty()) return "tenant";
        return cleaned;
    }
}
