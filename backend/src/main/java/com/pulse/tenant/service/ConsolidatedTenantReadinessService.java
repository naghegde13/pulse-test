package com.pulse.tenant.service;

import com.pulse.auth.service.TenantGcpConfigService;
import com.pulse.auth.service.TenantGcpCredentialService;
import com.pulse.auth.service.TenantService;
import com.pulse.git.identity.GitHubPatValidationStatus;
import com.pulse.git.identity.UserGitIdentity;
import com.pulse.git.identity.UserGitIdentityRepository;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.service.GitHubRepoUrlValidator;
import com.pulse.secret.model.SecretAuthorityReadiness;
import com.pulse.secret.service.SecretAuthorityReadinessService;
import com.pulse.tenant.model.ConsolidatedReadinessVerdict;
import com.pulse.tenant.model.ReadinessBlocker;
import com.pulse.tenant.model.ReadinessCategory;
import com.pulse.tenant.model.ReadinessCategory.ResourceOwnership;
import com.pulse.tenant.model.TenantGcpRuntimeTopology;
import com.pulse.tenant.repository.TenantGcpRuntimeTopologyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PKT-0015: Consolidated Tenant Pipeline-Development Readiness Verdict.
 *
 * <p>Aggregates all tenant platform prerequisites into a single fail-closed
 * gate covering 16 categories. Consumes existing subsystem services from
 * PKT-0010/0011/0012/0013/0014/0025 without reimplementing their logic.
 *
 * <p>Design rules:
 * <ul>
 *   <li>Fail-closed: any required category not ready blocks overall verdict.</li>
 *   <li>Non-mutating: no GCP writes, GitHub pushes, deploys, or secret-bearing ops.</li>
 *   <li>Redaction-safe: no PATs, private keys, JDBC passwords, or credential JSON.</li>
 *   <li>Package/deploy preflight is NOT invoked for Scenario 0.</li>
 *   <li>Consumes existing services; does not reimplement business logic.</li>
 * </ul>
 */
@Service
public class ConsolidatedTenantReadinessService {

    private static final Set<String> CREDENTIAL_SAFE_FIELDS = Set.of(
            "status", "serviceAccountEmail", "keyId", "gcpProjectId");

    private final TenantService tenantService;
    private final TenantGcpConfigService gcpConfigService;
    private final TenantGcpCredentialService gcpCredentialService;
    private final GitRepoRepository gitRepoRepository;
    private final GitHubRepoUrlValidator gitHubRepoUrlValidator;
    private final UserGitIdentityRepository userGitIdentityRepository;
    private final GcpIamManifestService iamManifestService;
    private final GcpRuntimeTopologyService topologyService;
    private final TenantGcpRuntimeTopologyRepository topologyRepository;
    private final DomainReadinessService domainReadinessService;
    private final TenantRuntimeReadinessService runtimeReadinessService;
    private final SecretAuthorityReadinessService secretAuthorityReadinessService;
    /**
     * PKT-FINAL-3 (BUG-08): when the GitHub client is in stub mode every PAT
     * validates as PROVIDER_UNAVAILABLE, so the readiness verdict needs to
     * surface that as a distinct, actionable blocker instead of looking like
     * a generic identity failure.
     */
    private final boolean githubClientEnabled;

    public ConsolidatedTenantReadinessService(
            TenantService tenantService,
            TenantGcpConfigService gcpConfigService,
            TenantGcpCredentialService gcpCredentialService,
            GitRepoRepository gitRepoRepository,
            GitHubRepoUrlValidator gitHubRepoUrlValidator,
            UserGitIdentityRepository userGitIdentityRepository,
            GcpIamManifestService iamManifestService,
            GcpRuntimeTopologyService topologyService,
            TenantGcpRuntimeTopologyRepository topologyRepository,
            DomainReadinessService domainReadinessService,
            TenantRuntimeReadinessService runtimeReadinessService,
            SecretAuthorityReadinessService secretAuthorityReadinessService,
            @Value("${pulse.git.github.enabled:false}") boolean githubClientEnabled) {
        this.tenantService = tenantService;
        this.gcpConfigService = gcpConfigService;
        this.gcpCredentialService = gcpCredentialService;
        this.gitRepoRepository = gitRepoRepository;
        this.gitHubRepoUrlValidator = gitHubRepoUrlValidator;
        this.userGitIdentityRepository = userGitIdentityRepository;
        this.iamManifestService = iamManifestService;
        this.topologyService = topologyService;
        this.topologyRepository = topologyRepository;
        this.domainReadinessService = domainReadinessService;
        this.runtimeReadinessService = runtimeReadinessService;
        this.secretAuthorityReadinessService = secretAuthorityReadinessService;
        this.githubClientEnabled = githubClientEnabled;
    }

    /**
     * Compute the consolidated readiness verdict for a tenant.
     * All 16 categories are evaluated and aggregated into a fail-closed gate.
     */
    public ConsolidatedReadinessVerdict computeVerdict(String tenantId) {
        // Cache shared lookups to avoid redundant queries
        Map<String, Object> domainReadinessCache = domainReadinessService.buildAllDomainReadiness(tenantId);
        Optional<TenantGcpRuntimeTopology> topologyCache = topologyRepository.findByTenantId(tenantId);

        List<ReadinessCategory> categories = new ArrayList<>();

        categories.add(buildTenantIdentity(tenantId));
        categories.add(buildGcpConfig(tenantId));
        categories.add(buildGcpCredentials(tenantId));
        categories.add(buildIamManifest(tenantId, topologyCache));
        categories.add(buildGithubRepo(tenantId));
        categories.add(buildGithubPat(tenantId));
        categories.add(buildGitScaffold(tenantId, domainReadinessCache));
        categories.add(buildStorageScaffold(tenantId, domainReadinessCache));
        categories.add(buildDomainScaffold(tenantId, domainReadinessCache));
        categories.add(buildComposer(tenantId, topologyCache));
        categories.add(buildDataproc(tenantId, topologyCache));
        categories.add(buildBigQuery(tenantId, topologyCache));
        categories.add(buildSecretManager(tenantId, topologyCache));
        categories.add(buildRuntimeBinding(tenantId));
        categories.add(buildDeploymentTarget(tenantId));
        categories.add(buildEvidenceLogging(tenantId, topologyCache));

        return ConsolidatedReadinessVerdict.build(tenantId, categories);
    }

    // -----------------------------------------------------------------------
    //  Category builders — each consumes existing subsystem services
    // -----------------------------------------------------------------------

    ReadinessCategory buildTenantIdentity(String tenantId) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        try {
            var tenant = tenantService.getTenantEntity(tenantId);
            evidence.put("id", tenant.getId());
            evidence.put("name", tenant.getName());
            evidence.put("slug", tenant.getSlug());
            evidence.put("origin", tenant.getOrigin());
            return ReadinessCategory.ready("tenantIdentity", evidence);
        } catch (Exception e) {
            return ReadinessCategory.blocked("tenantIdentity",
                    List.of(ReadinessBlocker.of("TENANT_NOT_FOUND",
                            "Tenant not found: " + tenantId,
                            "TenantService",
                            "GET /api/v1/tenants/" + tenantId,
                            "Configure tenant in application.yml pulse.tenants.definitions",
                            true)),
                    evidence);
        }
    }

    ReadinessCategory buildGcpConfig(String tenantId) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        var config = gcpConfigService.getConfig(tenantId);
        if (config.isPresent()) {
            var c = config.get();
            evidence.put("gcpProjectId", c.getControlPlaneProjectId());
            evidence.put("gcpRegion", c.getGcpRegion());
            evidence.put("source", "tenant_gcp_config");
            return ReadinessCategory.ready("gcpConfig", evidence);
        }
        evidence.put("source", "tenant_gcp_config");
        return ReadinessCategory.blocked("gcpConfig",
                List.of(ReadinessBlocker.of("MISSING_GCP_CONFIG",
                        "Tenant GCP config not set. Storage-backend gcpProject does not substitute.",
                        "TenantGcpConfigService",
                        "GET /api/v1/tenants/" + tenantId + "/gcp-config",
                        "PUT /api/v1/tenants/" + tenantId + "/gcp-config",
                        true)),
                evidence);
    }

    ReadinessCategory buildGcpCredentials(String tenantId) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        var cred = gcpCredentialService.getRedactedCredential(tenantId);
        if (cred.isPresent()) {
            var c = cred.get();
            for (String key : CREDENTIAL_SAFE_FIELDS) {
                if (c.containsKey(key)) {
                    evidence.put(key, c.get(key));
                }
            }
            evidence.put("privateKeyRedacted", true);
            if ("active".equals(c.get("status"))) {
                return ReadinessCategory.ready("gcpCredentials", evidence);
            }
            return ReadinessCategory.blocked("gcpCredentials",
                    List.of(ReadinessBlocker.of("GCP_CREDENTIAL_NOT_ACTIVE",
                            "GCP credential exists but status is '" + c.get("status") + "'",
                            "TenantGcpCredentialService",
                            "GET /api/v1/tenants/" + tenantId + "/gcp-credentials",
                            "PUT /api/v1/tenants/" + tenantId + "/gcp-credentials with active credential",
                            true)),
                    evidence);
        }
        evidence.put("privateKeyRedacted", true);
        return ReadinessCategory.blocked("gcpCredentials",
                List.of(ReadinessBlocker.of("MISSING_GCP_CREDENTIALS",
                        "No GCP credential configured for tenant",
                        "TenantGcpCredentialService",
                        "GET /api/v1/tenants/" + tenantId + "/gcp-credentials",
                        "PUT /api/v1/tenants/" + tenantId + "/gcp-credentials",
                        true)),
                evidence);
    }

    ReadinessCategory buildIamManifest(String tenantId, Optional<TenantGcpRuntimeTopology> topologyCache) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        var manifest = iamManifestService.generateManifest(tenantId);
        String status = (String) manifest.get("status");
        evidence.put("manifestStatus", status);
        if ("generated".equals(status)) {
            evidence.put("iamBindingExecution", manifest.get("iamBindingExecution"));
            @SuppressWarnings("unchecked")
            var saMap = (Map<String, Object>) manifest.get("serviceAccounts");
            if (saMap != null) {
                evidence.put("serviceAccountCount", saMap.size());
            }
            return ReadinessCategory.ready("iamManifest", evidence)
                    .withOwnership(new ResourceOwnership("IAM Bindings", "operator", "pulse"));
        }
        return ReadinessCategory.blocked("iamManifest",
                List.of(ReadinessBlocker.of("IAM_MANIFEST_NOT_GENERATED",
                        "IAM manifest cannot be generated: " + manifest.getOrDefault("error", "unknown"),
                        "GcpIamManifestService",
                        "GET /api/v1/tenants/" + tenantId + "/gcp-iam-manifest",
                        "Configure GCP runtime topology first via PUT /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                        true)),
                evidence);
    }

    ReadinessCategory buildGithubRepo(String tenantId) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        var repo = gitRepoRepository.findByTenantIdAndScope(tenantId, "TENANT");
        if (repo.isPresent()) {
            GitRepo r = repo.get();
            evidence.put("repoUrl", r.getRepoUrl());
            evidence.put("repoType", r.getRepoType());
            evidence.put("provider", r.getProvider());
            evidence.put("defaultBranch", r.getDefaultBranch());
            if ("REMOTE".equals(r.getRepoType()) && r.getRepoUrl() != null) {
                var validation = gitHubRepoUrlValidator.validate(r.getRepoUrl());
                evidence.put("githubUrlValid", validation.valid());
                if (!validation.valid()) {
                    return ReadinessCategory.blocked("githubRepo",
                            List.of(ReadinessBlocker.of("GITHUB_URL_INVALID",
                                    "GitHub repo URL invalid: " + validation.reason(),
                                    "GitHubRepoUrlValidator",
                                    "GET /api/v1/tenants/" + tenantId + "/readiness/foundation",
                                    "Fix repo URL via tenant git repo config",
                                    false)),
                            evidence);
                }
            }
            return ReadinessCategory.ready("githubRepo", evidence);
        }
        return ReadinessCategory.blocked("githubRepo",
                List.of(ReadinessBlocker.of("MISSING_GITHUB_REPO",
                        "No tenant-scoped git repo linked",
                        "GitRepoRepository",
                        "GET /api/v1/tenants/" + tenantId + "/readiness/foundation",
                        "Link a tenant git repo via the Git onboarding surface",
                        true)),
                evidence);
    }

    ReadinessCategory buildGithubPat(String tenantId) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("githubClientEnabled", githubClientEnabled);
        List<UserGitIdentity> identities = userGitIdentityRepository
                .findByTenantIdOrderByCreatedAtDesc(tenantId);

        // PKT-FINAL-3 (BUG-08): if the real GitHub HTTP client is disabled,
        // *no* PAT can validate. Surface that as the distinct blocker so the
        // operator fixes the configuration rather than rotating credentials
        // against a stub.
        if (!githubClientEnabled) {
            evidence.put("identityCount", identities.size());
            evidence.put("patRedacted", true);
            return ReadinessCategory.blocked("githubPat",
                    List.of(ReadinessBlocker.of("GITHUB_CLIENT_STUB_ACTIVE",
                            "PULSE GitHub client is in stub mode (pulse.git.github.enabled=false). "
                                    + "PAT validation cannot succeed in this configuration.",
                            "GitHubProviderAdapter",
                            "GET /api/v1/tenants/" + tenantId + "/readiness/consolidated",
                            "Set PULSE_GIT_GITHUB_ENABLED=true and restart the backend",
                            true)),
                    evidence);
        }

        if (identities.isEmpty()) {
            return ReadinessCategory.blocked("githubPat",
                    List.of(ReadinessBlocker.of("MISSING_GITHUB_PAT",
                            "No GitHub PAT identities registered for tenant",
                            "UserGitIdentityService",
                            "GET /api/v1/users/me/git-identity",
                            "Register a GitHub PAT via POST /api/v1/users/me/git-identity",
                            false)),
                    evidence);
        }

        boolean anyValid = identities.stream()
                .anyMatch(i -> GitHubPatValidationStatus.VALID.name().equals(i.getStatus()));
        evidence.put("identityCount", identities.size());
        evidence.put("anyValid", anyValid);
        // Never include PAT values, credential references, or secret IDs
        evidence.put("patRedacted", true);

        if (anyValid) {
            return ReadinessCategory.ready("githubPat", evidence);
        }
        return ReadinessCategory.blocked("githubPat",
                List.of(ReadinessBlocker.of("NO_VALID_GITHUB_PAT",
                        "GitHub PAT identities exist but none are VALID",
                        "UserGitIdentityService",
                        "GET /api/v1/users/me/git-identity",
                        "Rotate or re-register PAT via POST /api/v1/users/me/git-identity/rotate",
                        false)),
                evidence);
    }

    ReadinessCategory buildGitScaffold(String tenantId, Map<String, Object> domainReadiness) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("domainCount", domainReadiness.get("domainCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> domains = (List<Map<String, Object>>) domainReadiness.get("domains");
        if (domains != null) {
            long scaffolded = domains.stream()
                    .map(d -> {
                        @SuppressWarnings("unchecked")
                        var gs = (Map<String, Object>) d.get("gitScaffold");
                        return gs != null ? gs.get("status") : null;
                    })
                    .filter("scaffolded"::equals)
                    .count();
            evidence.put("gitScaffoldedDomainCount", scaffolded);
        }

        // BUG-72: this category must report on its OWN readiness (per-domain
        // git scaffold status), not on the combined per-domain readiness
        // boolean which also folds in unrelated checks (storage scaffold,
        // etc.). Cross-reading the combined `ready` field is the bug — it
        // produces a "Git Scaffold = FAIL" verdict with a misleading
        // GIT_SCAFFOLD_INCOMPLETE code even when every domain IS in fact
        // git-scaffolded, masking the real (storage) blocker.
        //
        // Vacuous case: zero domains → READY. The storageScaffold and
        // domainScaffold categories surface the NO_DOMAINS blocker; we do
        // not duplicate it here.
        boolean allGitScaffolded = domains == null
                || domains.isEmpty()
                || domains.stream().allMatch(d -> {
                    @SuppressWarnings("unchecked")
                    var gs = (Map<String, Object>) d.get("gitScaffold");
                    return gs != null && "scaffolded".equals(gs.get("status"));
                });

        if (allGitScaffolded) {
            return ReadinessCategory.ready("gitScaffold", evidence);
        }

        List<ReadinessBlocker> blockers = new ArrayList<>();
        for (Map<String, Object> d : domains) {
            @SuppressWarnings("unchecked")
            var gs = (Map<String, Object>) d.get("gitScaffold");
            if (gs != null && !"scaffolded".equals(gs.get("status"))) {
                blockers.add(ReadinessBlocker.of("GIT_SCAFFOLD_INCOMPLETE",
                        "Domain '" + d.get("domainSlug") + "' git scaffold status: " + gs.get("status"),
                        "DomainReadinessService",
                        "GET /api/v1/tenants/" + tenantId + "/domains/" + d.get("domainId") + "/readiness",
                        "Scaffold domain via Git scaffold surface",
                        "blocked".equals(gs.get("status"))));
            }
        }
        // Defensive: every code path above that reaches here has produced at
        // least one specific blocker (because allGitScaffolded would have
        // been true otherwise). Keep the generic fallback only as a guard
        // against future refactors that introduce new non-scaffolded
        // statuses we haven't yet enumerated.
        if (blockers.isEmpty()) {
            blockers.add(ReadinessBlocker.of("GIT_SCAFFOLD_INCOMPLETE",
                    "Git scaffold incomplete (no per-domain detail available)",
                    "DomainReadinessService",
                    "GET /api/v1/tenants/" + tenantId + "/readiness",
                    "Review per-domain git scaffold status",
                    false));
        }
        return ReadinessCategory.blocked("gitScaffold", blockers, evidence);
    }

    ReadinessCategory buildStorageScaffold(String tenantId, Map<String, Object> domainReadiness) {
        Map<String, Object> evidence = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> domains = (List<Map<String, Object>>) domainReadiness.get("domains");
        if (domains == null || domains.isEmpty()) {
            return ReadinessCategory.blocked("storageScaffold",
                    List.of(ReadinessBlocker.of("NO_DOMAINS",
                            "No domains configured for tenant",
                            "DomainReadinessService",
                            "GET /api/v1/tenants/" + tenantId + "/readiness",
                            "Create domains for the tenant",
                            false)),
                    evidence);
        }

        List<ReadinessBlocker> blockers = new ArrayList<>();
        long executedCount = 0;
        for (Map<String, Object> d : domains) {
            @SuppressWarnings("unchecked")
            var ss = (Map<String, Object>) d.get("storageScaffold");
            if (ss != null) {
                String status = (String) ss.get("status");
                if ("executed".equals(status)) {
                    executedCount++;
                } else {
                    boolean opRequired = "operator_blocked".equals(status);
                    blockers.add(ReadinessBlocker.of("STORAGE_SCAFFOLD_INCOMPLETE",
                            "Domain '" + d.get("domainSlug") + "' storage scaffold status: " + status,
                            "DomainReadinessService",
                            "GET /api/v1/tenants/" + tenantId + "/domains/" + d.get("domainId") + "/readiness",
                            opRequired ? "Operator must enable live GCS writes" : "Execute storage scaffold",
                            opRequired));
                }
            }
        }
        evidence.put("domainCount", domains.size());
        evidence.put("executedCount", executedCount);

        if (blockers.isEmpty()) {
            return ReadinessCategory.ready("storageScaffold", evidence)
                    .withOwnership(new ResourceOwnership("GCS Buckets/Paths", "operator+pulse", "pulse"));
        }
        return ReadinessCategory.blocked("storageScaffold", blockers, evidence)
                .withOwnership(new ResourceOwnership("GCS Buckets/Paths", "operator+pulse", "pulse"));
    }

    ReadinessCategory buildDomainScaffold(String tenantId, Map<String, Object> domainReadiness) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        Boolean allReady = (Boolean) domainReadiness.get("ready");
        evidence.put("domainCount", domainReadiness.get("domainCount"));
        evidence.put("allDomainsReady", allReady);

        if (Boolean.TRUE.equals(allReady)) {
            return ReadinessCategory.ready("domainScaffold", evidence);
        }

        int domainCount = domainReadiness.get("domainCount") != null
                ? (int) domainReadiness.get("domainCount") : 0;
        if (domainCount == 0) {
            return ReadinessCategory.blocked("domainScaffold",
                    List.of(ReadinessBlocker.of("NO_DOMAINS",
                            "No domains found for tenant",
                            "DomainReadinessService",
                            "GET /api/v1/tenants/" + tenantId + "/readiness",
                            "Create domains for the tenant",
                            false)),
                    evidence);
        }

        return ReadinessCategory.blocked("domainScaffold",
                List.of(ReadinessBlocker.of("DOMAIN_SCAFFOLD_INCOMPLETE",
                        "Not all domains are fully scaffolded (git + storage)",
                        "DomainReadinessService",
                        "GET /api/v1/tenants/" + tenantId + "/readiness",
                        "Complete scaffolding for all domains",
                        false)),
                evidence);
    }

    ReadinessCategory buildComposer(String tenantId, Optional<TenantGcpRuntimeTopology> opt) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (opt.isEmpty()) {
            return ReadinessCategory.notConfigured("composer",
                    List.of(ReadinessBlocker.of("MISSING_COMPOSER_TOPOLOGY",
                            "No GCP runtime topology configured — Composer section missing",
                            "GcpRuntimeTopologyService",
                            "GET /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                            "PUT /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                            true)),
                    evidence);
        }

        TenantGcpRuntimeTopology t = opt.get();
        evidence.put("projectId", t.getComposerProjectId());
        evidence.put("environment", t.getComposerEnvironment());
        evidence.put("region", t.getComposerRegion());
        evidence.put("hasBucket", !isBlank(t.getComposerEnvironmentBucket()));

        List<ReadinessBlocker> blockers = new ArrayList<>();
        if (isBlank(t.getComposerProjectId())) {
            blockers.add(ReadinessBlocker.of("MISSING_COMPOSER_PROJECT",
                    "Composer projectId not configured", "GcpRuntimeTopologyService",
                    "GET /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                    "Set composerProjectId in topology config", true));
        }
        if (isBlank(t.getComposerEnvironment())) {
            blockers.add(ReadinessBlocker.of("MISSING_COMPOSER_ENVIRONMENT",
                    "Composer environment name not configured", "GcpRuntimeTopologyService",
                    "GET /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                    "Set composerEnvironment in topology config", true));
        }
        if (isBlank(t.getComposerRegion())) {
            blockers.add(ReadinessBlocker.of("MISSING_COMPOSER_REGION",
                    "Composer region not configured", "GcpRuntimeTopologyService",
                    "GET /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                    "Set composerRegion in topology config", true));
        }

        if (blockers.isEmpty()) {
            return ReadinessCategory.ready("composer", evidence)
                    .withOwnership(new ResourceOwnership("Composer Environment", "operator", "pulse"));
        }
        return ReadinessCategory.blocked("composer", blockers, evidence)
                .withOwnership(new ResourceOwnership("Composer Environment", "operator", "pulse"));
    }

    ReadinessCategory buildDataproc(String tenantId, Optional<TenantGcpRuntimeTopology> opt) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (opt.isEmpty()) {
            return ReadinessCategory.notConfigured("dataproc",
                    List.of(ReadinessBlocker.of("MISSING_DATAPROC_TOPOLOGY",
                            "No GCP runtime topology configured — Dataproc section missing",
                            "GcpRuntimeTopologyService",
                            "GET /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                            "PUT /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                            true)),
                    evidence);
        }

        TenantGcpRuntimeTopology t = opt.get();
        evidence.put("projectId", t.getDataprocProjectId());
        evidence.put("region", t.getDataprocRegion());
        evidence.put("hasWorkloadSa", !isBlank(t.getDataprocWorkloadSaEmail()));
        evidence.put("hasStagingBucket", !isBlank(t.getDataprocStagingBucket()));

        List<ReadinessBlocker> blockers = new ArrayList<>();
        if (isBlank(t.getDataprocProjectId())) {
            blockers.add(ReadinessBlocker.of("MISSING_DATAPROC_PROJECT",
                    "Dataproc projectId not configured", "GcpRuntimeTopologyService",
                    "GET /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                    "Set dataprocProjectId in topology config", true));
        }
        if (isBlank(t.getDataprocRegion())) {
            blockers.add(ReadinessBlocker.of("MISSING_DATAPROC_REGION",
                    "Dataproc region not configured", "GcpRuntimeTopologyService",
                    "GET /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                    "Set dataprocRegion in topology config", true));
        }
        if (isBlank(t.getDataprocWorkloadSaEmail())) {
            blockers.add(ReadinessBlocker.of("MISSING_DATAPROC_WORKLOAD_SA",
                    "Dataproc workload service account not configured", "GcpRuntimeTopologyService",
                    "GET /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                    "Set dataprocWorkloadSaEmail in topology config", true));
        }

        if (blockers.isEmpty()) {
            return ReadinessCategory.ready("dataproc", evidence)
                    .withOwnership(new ResourceOwnership("Dataproc Serverless", "operator", "pulse"));
        }
        return ReadinessCategory.blocked("dataproc", blockers, evidence)
                .withOwnership(new ResourceOwnership("Dataproc Serverless", "operator", "pulse"));
    }

    ReadinessCategory buildBigQuery(String tenantId, Optional<TenantGcpRuntimeTopology> opt) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (opt.isEmpty()) {
            return ReadinessCategory.notConfigured("bigQuery",
                    List.of(ReadinessBlocker.of("MISSING_BQ_TOPOLOGY",
                            "No GCP runtime topology configured — BigQuery section missing",
                            "GcpRuntimeTopologyService",
                            "GET /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                            "PUT /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                            true)),
                    evidence);
        }

        TenantGcpRuntimeTopology t = opt.get();
        evidence.put("projectId", t.getBqProjectId());
        evidence.put("location", t.getBqLocation());
        evidence.put("hasDatasets", !isBlank(t.getBqDatasetBronze()));
        evidence.put("hasConnection", !isBlank(t.getBqConnectionId()));

        List<ReadinessBlocker> blockers = new ArrayList<>();
        if (isBlank(t.getBqProjectId())) {
            blockers.add(ReadinessBlocker.of("MISSING_BQ_PROJECT",
                    "BigQuery projectId not configured", "GcpRuntimeTopologyService",
                    "GET /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                    "Set bqProjectId in topology config", true));
        }
        if (isBlank(t.getBqLocation())) {
            blockers.add(ReadinessBlocker.of("MISSING_BQ_LOCATION",
                    "BigQuery location not configured", "GcpRuntimeTopologyService",
                    "GET /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                    "Set bqLocation in topology config", true));
        }
        if (isBlank(t.getBqDatasetBronze()) || isBlank(t.getBqDatasetSilver()) || isBlank(t.getBqDatasetGold())) {
            blockers.add(ReadinessBlocker.of("MISSING_BQ_MEDALLION_DATASETS",
                    "BigQuery medallion datasets incomplete (bronze/silver/gold required)", "GcpRuntimeTopologyService",
                    "GET /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                    "Set bqDatasetBronze, bqDatasetSilver, bqDatasetGold in topology config", true));
        }

        if (blockers.isEmpty()) {
            return ReadinessCategory.ready("bigQuery", evidence)
                    .withOwnership(new ResourceOwnership("BigQuery Datasets", "operator", "pulse"));
        }
        return ReadinessCategory.blocked("bigQuery", blockers, evidence)
                .withOwnership(new ResourceOwnership("BigQuery Datasets", "operator", "pulse"));
    }

    ReadinessCategory buildSecretManager(String tenantId, Optional<TenantGcpRuntimeTopology> topologyCache) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        SecretAuthorityReadiness sar = secretAuthorityReadinessService.computeForTenant(tenantId);
        evidence.put("mode", sar.mode().name());
        evidence.put("proofStatus", sar.proofStatus().name());
        evidence.put("credentialSource", sar.credentialSource());
        // Only copy redaction-safe context fields
        Map<String, Object> ctx = sar.redactedContext();
        if (ctx.containsKey("gcpProjectId")) {
            evidence.put("gcpProjectId", ctx.get("gcpProjectId"));
        }
        if (ctx.containsKey("gcpBackedProof")) {
            evidence.put("gcpBackedProof", ctx.get("gcpBackedProof"));
        }

        // PKT-FINAL-5 / BUG-54: surface the per-tenant secret manager
        // binding state (mode + GSM project id) so the wizard can render an
        // actionable next-step instead of generic "configure mode" copy.
        String gsmProjectId = null;
        String tenantMode = null;
        String secretNamePrefix = null;
        if (topologyCache.isPresent()) {
            var t = topologyCache.get();
            tenantMode = t.getSecretAuthorityMode();
            gsmProjectId = t.getSecretManagerProjectId();
            secretNamePrefix = t.getSecretNamePrefix();
            if (!isBlank(gsmProjectId)) {
                evidence.put("topologySmProjectId", gsmProjectId);
                evidence.put("gsmProjectId", gsmProjectId);
            }
            if (!isBlank(tenantMode)) {
                evidence.put("tenantBindingMode", tenantMode);
            }
            if (!isBlank(secretNamePrefix)) {
                evidence.put("secretNamePrefix", secretNamePrefix);
            }
        }

        if (sar.isReady()) {
            return ReadinessCategory.ready("secretManager", evidence)
                    .withOwnership(new ResourceOwnership("Secret Manager", "operator", "pulse"));
        }

        String message;
        String recommended;
        if (sar.proofStatus() == SecretAuthorityReadiness.ProofStatus.NON_PROOF) {
            message = "Secret authority mode is local-stub (non-proof for GCP readiness)";
            recommended = "PUT /api/v1/tenants/" + tenantId
                    + "/secret-manager with mode=GCP_SECRET_MANAGER and gsmProjectId";
        } else if ("GCP_SECRET_MANAGER".equalsIgnoreCase(tenantMode) && isBlank(gsmProjectId)) {
            message = "Secret authority mode is GCP_SECRET_MANAGER but no gsmProjectId is set";
            recommended = "PUT /api/v1/tenants/" + tenantId
                    + "/secret-manager with gsmProjectId=<your-secrets-project>";
        } else if (!isBlank(gsmProjectId)) {
            message = "Secret authority is blocked: " + ctx.getOrDefault("blockerReason", "unknown");
            recommended = "gcloud projects add-iam-policy-binding " + gsmProjectId
                    + " --member=serviceAccount:<tenant-sa> "
                    + "--role=roles/secretmanager.secretAccessor";
        } else {
            message = "Secret authority is blocked: " + ctx.getOrDefault("blockerReason", "unknown");
            recommended = "Configure GCP Secret Manager mode and submit tenant credentials";
        }

        return ReadinessCategory.blocked("secretManager",
                List.of(ReadinessBlocker.of("SECRET_MANAGER_NOT_READY",
                        message,
                        "SecretAuthorityReadinessService",
                        "GET /api/v1/tenants/" + tenantId + "/secret-manager",
                        recommended,
                        true)),
                evidence)
                .withOwnership(new ResourceOwnership("Secret Manager", "operator", "pulse"));
    }

    ReadinessCategory buildRuntimeBinding(String tenantId) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        var category = runtimeReadinessService.buildRuntimeBindingCategory(tenantId);
        String status = (String) category.get("status");
        evidence.put("activePrimaryCount", category.get("activePrimaryCount"));
        evidence.put("anyValidated", category.get("anyValidated"));
        evidence.put("gcpLiveReady", category.get("gcpLiveReady"));

        if ("ready".equals(status)) {
            return ReadinessCategory.ready("runtimeBinding", evidence);
        }

        List<ReadinessBlocker> blockers = new ArrayList<>();
        @SuppressWarnings("unchecked")
        var rawBlockers = (List<String>) category.get("blockers");
        if (rawBlockers != null) {
            for (String msg : rawBlockers) {
                blockers.add(ReadinessBlocker.of("RUNTIME_BINDING_INCOMPLETE",
                        msg, "TenantRuntimeReadinessService",
                        "GET /api/v1/tenants/" + tenantId + "/readiness",
                        "Configure runtime bindings via the runtime binding surface",
                        false));
            }
        }
        if (blockers.isEmpty()) {
            blockers.add(ReadinessBlocker.of("RUNTIME_BINDING_NOT_CONFIGURED",
                    "Runtime binding not ready: " + status, "TenantRuntimeReadinessService",
                    "GET /api/v1/tenants/" + tenantId + "/readiness",
                    "Configure runtime bindings", false));
        }
        return ReadinessCategory.blocked("runtimeBinding", blockers, evidence);
    }

    ReadinessCategory buildDeploymentTarget(String tenantId) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        var category = runtimeReadinessService.buildDeploymentTargetCategory(tenantId);
        String status = (String) category.get("status");
        evidence.put("enabledCount", category.get("enabledCount"));
        evidence.put("activePersona", category.get("activePersona"));

        if ("ready".equals(status)) {
            return ReadinessCategory.ready("deploymentTarget", evidence);
        }

        List<ReadinessBlocker> blockers = new ArrayList<>();
        @SuppressWarnings("unchecked")
        var rawBlockers = (List<String>) category.get("blockers");
        if (rawBlockers != null) {
            for (String msg : rawBlockers) {
                blockers.add(ReadinessBlocker.of("DEPLOYMENT_TARGET_INCOMPLETE",
                        msg, "TenantRuntimeReadinessService",
                        "GET /api/v1/tenants/" + tenantId + "/readiness",
                        "Configure deployment targets", false));
            }
        }
        if (blockers.isEmpty()) {
            blockers.add(ReadinessBlocker.of("DEPLOYMENT_TARGET_NOT_CONFIGURED",
                    "Deployment target not ready: " + status, "TenantRuntimeReadinessService",
                    "GET /api/v1/tenants/" + tenantId + "/readiness",
                    "Configure deployment targets", false));
        }
        return ReadinessCategory.blocked("deploymentTarget", blockers, evidence);
    }

    ReadinessCategory buildEvidenceLogging(String tenantId, Optional<TenantGcpRuntimeTopology> opt) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (opt.isEmpty()) {
            return ReadinessCategory.notConfigured("evidenceLogging",
                    List.of(ReadinessBlocker.of("MISSING_EVIDENCE_TOPOLOGY",
                            "No GCP runtime topology configured — evidence/logging section missing",
                            "GcpRuntimeTopologyService",
                            "GET /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                            "PUT /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                            true)),
                    evidence);
        }

        TenantGcpRuntimeTopology t = opt.get();
        evidence.put("hasEvidenceSinkBucket", !isBlank(t.getEvidenceSinkBucket()));
        evidence.put("hasEvidenceSinkDataset", !isBlank(t.getEvidenceSinkDataset()));
        evidence.put("hasLoggingProject", !isBlank(t.getLoggingProjectId()));
        evidence.put("hasLogBucket", !isBlank(t.getLoggingLogBucket()));

        List<ReadinessBlocker> blockers = new ArrayList<>();
        if (isBlank(t.getEvidenceSinkBucket()) && isBlank(t.getEvidenceSinkDataset())) {
            blockers.add(ReadinessBlocker.of("MISSING_EVIDENCE_SINK",
                    "Evidence sink not configured: at least one of bucket or dataset required",
                    "GcpRuntimeTopologyService",
                    "GET /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                    "Set evidenceSinkBucket or evidenceSinkDataset in topology config",
                    true));
        }
        if (isBlank(t.getLoggingProjectId())) {
            blockers.add(ReadinessBlocker.of("MISSING_LOGGING_PROJECT",
                    "Logging projectId not configured",
                    "GcpRuntimeTopologyService",
                    "GET /api/v1/tenants/" + tenantId + "/gcp-runtime-topology",
                    "Set loggingProjectId in topology config",
                    true));
        }

        if (blockers.isEmpty()) {
            return ReadinessCategory.ready("evidenceLogging", evidence)
                    .withOwnership(new ResourceOwnership("Evidence/Log Resources", "operator", "pulse"));
        }
        return ReadinessCategory.blocked("evidenceLogging", blockers, evidence)
                .withOwnership(new ResourceOwnership("Evidence/Log Resources", "operator", "pulse"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
