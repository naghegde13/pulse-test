package com.pulse.tenant.controller;

import com.pulse.git.service.GitHubRepoUrlValidator;
import com.pulse.storage.service.StorageScaffoldService;
import com.pulse.tenant.model.ConsolidatedReadinessVerdict;
import com.pulse.tenant.model.ReadinessBlocker;
import com.pulse.tenant.model.ReadinessCategory;
import com.pulse.tenant.service.ConsolidatedTenantReadinessService;
import com.pulse.tenant.service.DomainReadinessService;
import com.pulse.tenant.service.GcpRuntimeTopologyService;
import com.pulse.tenant.service.TenantReadinessFoundationService;
import com.pulse.tenant.service.TenantRuntimeReadinessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * PKT-0015: Controller contract tests for GET consolidated readiness.
 */
@ExtendWith(MockitoExtension.class)
class ConsolidatedReadinessControllerTest {

    private static final String TENANT = "acme-lending";

    @Mock private TenantReadinessFoundationService foundationService;
    @Mock private StorageScaffoldService storageScaffoldService;
    @Mock private DomainReadinessService domainReadinessService;
    @Mock private TenantRuntimeReadinessService runtimeReadinessService;
    @Mock private GcpRuntimeTopologyService gcpRuntimeTopologyService;
    @Mock private ConsolidatedTenantReadinessService consolidatedReadinessService;

    private TenantReadinessController controller;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        controller = new TenantReadinessController(
                foundationService, new GitHubRepoUrlValidator(),
                storageScaffoldService, domainReadinessService,
                runtimeReadinessService, gcpRuntimeTopologyService,
                consolidatedReadinessService);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("GET /readiness/consolidated returns 200 with verdict structure")
    void getConsolidatedReadiness_returns200() throws Exception {
        var verdict = makeReadyVerdict();
        when(consolidatedReadinessService.computeVerdict(TENANT)).thenReturn(verdict);

        ResponseEntity<ConsolidatedReadinessVerdict> response =
                controller.getConsolidatedReadiness(TENANT);

        assertEquals(200, response.getStatusCode().value());
        ConsolidatedReadinessVerdict body = response.getBody();
        assertNotNull(body);
        assertEquals(TENANT, body.tenantId());
        assertEquals("ready", body.overallStatus());
        assertEquals(16, body.totalCategoryCount());
        assertEquals(16, body.readyCategoryCount());
        assertTrue(body.blockerSummary().isEmpty());
        assertNotNull(body.checkedAt());

        // All 16 categories present
        var cats = body.categories();
        assertEquals(16, cats.size());
        assertNotNull(cats.get("tenantIdentity"));
        assertNotNull(cats.get("gcpConfig"));
        assertNotNull(cats.get("gcpCredentials"));
        assertNotNull(cats.get("iamManifest"));
        assertNotNull(cats.get("githubRepo"));
        assertNotNull(cats.get("githubPat"));
        assertNotNull(cats.get("gitScaffold"));
        assertNotNull(cats.get("storageScaffold"));
        assertNotNull(cats.get("domainScaffold"));
        assertNotNull(cats.get("composer"));
        assertNotNull(cats.get("dataproc"));
        assertNotNull(cats.get("bigQuery"));
        assertNotNull(cats.get("secretManager"));
        assertNotNull(cats.get("runtimeBinding"));
        assertNotNull(cats.get("deploymentTarget"));
        assertNotNull(cats.get("evidenceLogging"));
    }

    @Test
    @DisplayName("GET /readiness/consolidated with blockers returns blocked status with structured blockers")
    void getConsolidatedReadiness_blocked() throws Exception {
        var verdict = makeBlockedVerdict();
        when(consolidatedReadinessService.computeVerdict(TENANT)).thenReturn(verdict);

        ResponseEntity<ConsolidatedReadinessVerdict> response =
                controller.getConsolidatedReadiness(TENANT);

        assertEquals(200, response.getStatusCode().value());
        ConsolidatedReadinessVerdict body = response.getBody();
        assertNotNull(body);
        assertEquals("blocked", body.overallStatus());
        assertFalse(body.blockerSummary().isEmpty());

        ReadinessBlocker blocker = body.blockerSummary().get(0);
        assertNotNull(blocker.code());
        assertNotNull(blocker.message());
        assertNotNull(blocker.sourceSurface());
        assertNotNull(blocker.evidenceRef());
        assertNotNull(blocker.staleCheckTimestamp());
        assertNotNull(blocker.safeNextAction());
    }

    @Test
    @DisplayName("GET /readiness/consolidated response serializes without secret material")
    void getConsolidatedReadiness_noSecrets() throws Exception {
        var verdict = makeReadyVerdict();
        when(consolidatedReadinessService.computeVerdict(TENANT)).thenReturn(verdict);

        ResponseEntity<ConsolidatedReadinessVerdict> response =
                controller.getConsolidatedReadiness(TENANT);

        String json = objectMapper.writeValueAsString(response.getBody());

        assertFalse(json.contains("ghp_"), "Should not contain GitHub PAT");
        assertFalse(json.contains("PRIVATE KEY"), "Should not contain private key material");
        assertFalse(json.contains("client_secret"), "Should not contain credential JSON");
    }

    @Test
    @DisplayName("Verdict does not include package/deploy preflight categories")
    void noDeployPreflightCategories() throws Exception {
        var verdict = makeReadyVerdict();
        when(consolidatedReadinessService.computeVerdict(TENANT)).thenReturn(verdict);

        ResponseEntity<ConsolidatedReadinessVerdict> response =
                controller.getConsolidatedReadiness(TENANT);
        var cats = response.getBody().categories();

        assertFalse(cats.containsKey("packagePreflight"));
        assertFalse(cats.containsKey("deployPreflight"));
        assertFalse(cats.containsKey("deployReadiness"));
    }

    // ---- helpers ----

    private ConsolidatedReadinessVerdict makeReadyVerdict() {
        List<ReadinessCategory> cats = List.of(
                ReadinessCategory.ready("tenantIdentity", Map.of("id", TENANT)),
                ReadinessCategory.ready("gcpConfig", Map.of("gcpProjectId", "p")),
                ReadinessCategory.ready("gcpCredentials", Map.of("privateKeyRedacted", true)),
                ReadinessCategory.ready("iamManifest", Map.of("manifestStatus", "generated")),
                ReadinessCategory.ready("githubRepo", Map.of("repoUrl", "https://github.com/a/b")),
                ReadinessCategory.ready("githubPat", Map.of("patRedacted", true, "anyValid", true)),
                ReadinessCategory.ready("gitScaffold", Map.of("domainCount", 1)),
                ReadinessCategory.ready("storageScaffold", Map.of("executedCount", 1)),
                ReadinessCategory.ready("domainScaffold", Map.of("allDomainsReady", true)),
                ReadinessCategory.ready("composer", Map.of("projectId", "p")),
                ReadinessCategory.ready("dataproc", Map.of("projectId", "p")),
                ReadinessCategory.ready("bigQuery", Map.of("projectId", "p")),
                ReadinessCategory.ready("secretManager", Map.of("proofStatus", "PROVEN")),
                ReadinessCategory.ready("runtimeBinding", Map.of("gcpLiveReady", true)),
                ReadinessCategory.ready("deploymentTarget", Map.of("enabledCount", 1)),
                ReadinessCategory.ready("evidenceLogging", Map.of("hasEvidenceSinkBucket", true))
        );
        return ConsolidatedReadinessVerdict.build(TENANT, cats);
    }

    private ConsolidatedReadinessVerdict makeBlockedVerdict() {
        var blocker = ReadinessBlocker.of(
                "MISSING_GCP_CONFIG", "Tenant GCP config not set",
                "TenantGcpConfigService",
                "GET /api/v1/tenants/" + TENANT + "/gcp-config",
                "PUT /api/v1/tenants/" + TENANT + "/gcp-config",
                true);

        List<ReadinessCategory> cats = List.of(
                ReadinessCategory.ready("tenantIdentity", Map.of("id", TENANT)),
                ReadinessCategory.blocked("gcpConfig", List.of(blocker), Map.of()),
                ReadinessCategory.ready("gcpCredentials", Map.of("privateKeyRedacted", true)),
                ReadinessCategory.ready("iamManifest", Map.of()),
                ReadinessCategory.ready("githubRepo", Map.of()),
                ReadinessCategory.ready("githubPat", Map.of("patRedacted", true)),
                ReadinessCategory.ready("gitScaffold", Map.of()),
                ReadinessCategory.ready("storageScaffold", Map.of()),
                ReadinessCategory.ready("domainScaffold", Map.of()),
                ReadinessCategory.ready("composer", Map.of()),
                ReadinessCategory.ready("dataproc", Map.of()),
                ReadinessCategory.ready("bigQuery", Map.of()),
                ReadinessCategory.ready("secretManager", Map.of()),
                ReadinessCategory.ready("runtimeBinding", Map.of()),
                ReadinessCategory.ready("deploymentTarget", Map.of()),
                ReadinessCategory.ready("evidenceLogging", Map.of())
        );
        return ConsolidatedReadinessVerdict.build(TENANT, cats);
    }
}
