package com.pulse.tenant.controller;

import com.pulse.git.service.GitHubRepoUrlValidator;
import com.pulse.storage.service.StorageScaffoldService;
import com.pulse.tenant.service.DomainReadinessService;
import com.pulse.tenant.service.TenantReadinessFoundationService;
import com.pulse.tenant.service.TenantRuntimeReadinessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantReadinessControllerTest {

    @Mock private TenantReadinessFoundationService foundationService;
    @Mock private StorageScaffoldService storageScaffoldService;
    @Mock private DomainReadinessService domainReadinessService;
    @Mock private TenantRuntimeReadinessService runtimeReadinessService;
    @Mock private com.pulse.tenant.service.GcpRuntimeTopologyService gcpRuntimeTopologyService;
    @Mock private com.pulse.tenant.service.ConsolidatedTenantReadinessService consolidatedReadinessService;

    private TenantReadinessController controller;

    @BeforeEach
    void setUp() {
        controller = new TenantReadinessController(
                foundationService, new GitHubRepoUrlValidator(),
                storageScaffoldService, domainReadinessService,
                runtimeReadinessService, gcpRuntimeTopologyService,
                consolidatedReadinessService);
    }

    @Test
    void getFoundation_returns200() {
        Map<String, Object> foundation = new LinkedHashMap<>();
        foundation.put("tenantId", "t1");
        foundation.put("packet", "PKT-0010");
        when(foundationService.getFoundation("t1")).thenReturn(foundation);

        var response = controller.getFoundation("t1");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("PKT-0010", response.getBody().get("packet"));
    }

    @Test
    void validateGitRepoUrl_validGithubUrl_returnsTrue() {
        var request = new TenantReadinessController.ValidateUrlRequest(
                "https://github.com/zadam2008/pulse-acme-lending.git");
        var response = controller.validateGitRepoUrl("t1", request);

        assertEquals(200, response.getStatusCode().value());
        assertTrue((Boolean) response.getBody().get("valid"));
    }

    @Test
    void validateGitRepoUrl_localPath_returnsFalse() {
        var request = new TenantReadinessController.ValidateUrlRequest("/data/pulse/repos/acme");
        var response = controller.validateGitRepoUrl("t1", request);

        assertEquals(200, response.getStatusCode().value());
        assertFalse((Boolean) response.getBody().get("valid"));
        assertNotNull(response.getBody().get("reason"));
    }

    @Test
    void validateGitRepoUrl_nullUrl_returns400() {
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.validateGitRepoUrl("t1",
                        new TenantReadinessController.ValidateUrlRequest(null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void validateGitRepoUrl_nonGithubHost_returnsFalse() {
        var request = new TenantReadinessController.ValidateUrlRequest(
                "https://gitlab.com/owner/repo.git");
        var response = controller.validateGitRepoUrl("t1", request);

        assertFalse((Boolean) response.getBody().get("valid"));
    }

    // ---- PKT-0012: Readiness endpoint with storageScaffold category ----

    @Test
    void getReadiness_includesFoundationAndStorageScaffold() {
        Map<String, Object> foundation = new LinkedHashMap<>();
        foundation.put("tenantId", "t1");
        foundation.put("packet", "PKT-0010");
        when(foundationService.getFoundation("t1")).thenReturn(foundation);

        Map<String, Object> scaffoldCategory = new LinkedHashMap<>();
        scaffoldCategory.put("status", "previewed");
        scaffoldCategory.put("domainCount", 1);
        when(storageScaffoldService.buildReadinessCategory("t1")).thenReturn(scaffoldCategory);

        Map<String, Object> domainReadiness = new LinkedHashMap<>();
        domainReadiness.put("ready", false);
        domainReadiness.put("domainCount", 1);
        when(domainReadinessService.buildAllDomainReadiness("t1")).thenReturn(domainReadiness);

        Map<String, Object> bindingCategory = new LinkedHashMap<>();
        bindingCategory.put("status", "not_configured");
        when(runtimeReadinessService.buildRuntimeBindingCategory("t1")).thenReturn(bindingCategory);

        Map<String, Object> targetCategory = new LinkedHashMap<>();
        targetCategory.put("status", "not_configured");
        when(runtimeReadinessService.buildDeploymentTargetCategory("t1")).thenReturn(targetCategory);

        Map<String, Object> topologyCategory = new LinkedHashMap<>();
        topologyCategory.put("status", "not_configured");
        when(gcpRuntimeTopologyService.buildReadinessCategory("t1")).thenReturn(topologyCategory);

        var response = controller.getReadiness("t1");

        assertEquals(200, response.getStatusCode().value());
        var body = response.getBody();
        assertEquals("t1", body.get("tenantId"));
        assertNotNull(body.get("foundation"));
        assertNotNull(body.get("storageScaffold"));
        assertNotNull(body.get("domainReadiness"));
        assertNotNull(body.get("runtimeBinding"));
        assertNotNull(body.get("deploymentTarget"));

        @SuppressWarnings("unchecked")
        var scaffold = (Map<String, Object>) body.get("storageScaffold");
        assertEquals("previewed", scaffold.get("status"));
    }

    // ---- PKT-0013: Readiness endpoint includes domainReadiness category ----

    @Test
    void getReadiness_includesDomainReadiness() {
        when(foundationService.getFoundation("t1")).thenReturn(Map.of("tenantId", "t1"));
        when(storageScaffoldService.buildReadinessCategory("t1")).thenReturn(Map.of("status", "previewed"));
        when(runtimeReadinessService.buildRuntimeBindingCategory("t1")).thenReturn(Map.of("status", "not_configured"));
        when(runtimeReadinessService.buildDeploymentTargetCategory("t1")).thenReturn(Map.of("status", "not_configured"));

        Map<String, Object> domainReadiness = new LinkedHashMap<>();
        domainReadiness.put("tenantId", "t1");
        domainReadiness.put("packet", "PKT-0013");
        domainReadiness.put("ready", true);
        domainReadiness.put("domainCount", 1);
        domainReadiness.put("domains", List.of(Map.of("domainSlug", "lending", "ready", true)));
        when(domainReadinessService.buildAllDomainReadiness("t1")).thenReturn(domainReadiness);
        when(gcpRuntimeTopologyService.buildReadinessCategory("t1")).thenReturn(Map.of("status", "not_configured"));

        var response = controller.getReadiness("t1");
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody().get("domainReadiness"));

        @SuppressWarnings("unchecked")
        var dr = (Map<String, Object>) response.getBody().get("domainReadiness");
        assertEquals("PKT-0013", dr.get("packet"));
        assertTrue((Boolean) dr.get("ready"));
    }

    @Test
    void getDomainReadiness_returns200() {
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("domainId", "d1");
        readiness.put("ready", false);
        when(domainReadinessService.buildDomainReadiness("t1", "d1")).thenReturn(readiness);

        var response = controller.getDomainReadiness("t1", "d1");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("d1", response.getBody().get("domainId"));
        assertFalse((Boolean) response.getBody().get("ready"));
    }
}
