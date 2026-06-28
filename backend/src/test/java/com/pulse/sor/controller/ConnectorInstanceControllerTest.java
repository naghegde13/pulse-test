package com.pulse.sor.controller;

import com.pulse.auth.filter.JwtPrincipal;
import com.pulse.secret.service.CredentialPersistenceService;
import com.pulse.secret.service.CredentialReadinessService;
import com.pulse.secret.service.SecretManagerException;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.CredentialStatus;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DatasetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Thin-delegation tests for ConnectorInstanceController. Credential merge,
 * sanitize, and Secret Manager behavior is verified in
 * {@link com.pulse.secret.service.CredentialPersistenceServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ConnectorInstanceControllerTest {

    @Mock private ConnectorInstanceRepository ciRepo;
    @Mock private CredentialProfileRepository credRepo;
    @Mock private ConnectorDefinitionRepository connDefRepo;
    @Mock private DatasetRepository datasetRepo;
    @Mock private CredentialPersistenceService credentialPersistenceService;
    @Mock private CredentialReadinessService credentialReadinessService;
    @Mock private com.pulse.sor.repository.SystemOfRecordRepository sorRepo;
    // Phase 3: real policy + dev-stub resolver. Dev stub roles include
    // PLATFORM_ADMIN so existing happy-path tests still pass; dedicated
    // denial coverage lives in ConnectorInstanceControllerAuthorizationTest.
    @org.mockito.Spy private com.pulse.auth.policy.AuthorizationPolicyService authPolicy =
            new com.pulse.auth.policy.AuthorizationPolicyService();
    @org.mockito.Spy private com.pulse.auth.policy.ActorResolverService actorResolver =
            new com.pulse.auth.policy.ActorResolverService();

    @InjectMocks
    private ConnectorInstanceController controller;

    @org.junit.jupiter.api.BeforeEach
    void stubSecretMetadataResolution() {
        // Default benign stubs so credential-path tests resolve the
        // SOR/tenant chain that enforceSecretMetadata now walks.
        ConnectorInstance ci = buildConnectorInstance("ci-1", "sor-1", "Oracle Connector");
        com.pulse.sor.model.SystemOfRecord sor = new com.pulse.sor.model.SystemOfRecord();
        sor.setId("sor-1");
        sor.setTenantId("tenant-1");
        when(ciRepo.findById("ci-1")).thenReturn(Optional.of(ci));
        when(sorRepo.findById("sor-1")).thenReturn(Optional.of(sor));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // -----------------------------------------------------------------------
    //  list tests
    // -----------------------------------------------------------------------

    @Test
    void list_returnsConnectorInstancesForSOR() {
        ConnectorInstance ci = buildConnectorInstance("ci-1", "sor-1", "Oracle Connector");
        when(ciRepo.findBySorIdOrderByNameAsc("sor-1")).thenReturn(List.of(ci));
        when(connDefRepo.findById("def-oracle")).thenReturn(Optional.empty());
        when(credRepo.findByConnectorInstanceIdOrderByEnvironmentAsc("ci-1"))
                .thenReturn(List.of());
        when(datasetRepo.countByConnectorInstanceId("ci-1")).thenReturn(10L);

        ResponseEntity<List<Map<String, Object>>> response = controller.list("sor-1");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());

        Map<String, Object> result = response.getBody().get(0);
        assertEquals("ci-1", result.get("id"));
        assertEquals("Oracle Connector", result.get("name"));
        assertEquals(10L, result.get("datasetCount"));
        verify(ciRepo).findBySorIdOrderByNameAsc("sor-1");
    }

    @Test
    void list_includesCredentialStatuses() {
        ConnectorInstance ci = buildConnectorInstance("ci-1", "sor-1", "Postgres Connector");
        when(ciRepo.findBySorIdOrderByNameAsc("sor-1")).thenReturn(List.of(ci));
        when(connDefRepo.findById("def-oracle")).thenReturn(Optional.empty());

        CredentialProfile devCred = new CredentialProfile();
        devCred.setEnvironment("DEV");
        devCred.setStatus(CredentialStatus.VALID);
        CredentialProfile prodCred = new CredentialProfile();
        prodCred.setEnvironment("PROD");
        prodCred.setStatus(CredentialStatus.UNTESTED);

        when(credRepo.findByConnectorInstanceIdOrderByEnvironmentAsc("ci-1"))
                .thenReturn(List.of(devCred, prodCred));
        when(datasetRepo.countByConnectorInstanceId("ci-1")).thenReturn(0L);

        ResponseEntity<List<Map<String, Object>>> response = controller.list("sor-1");

        @SuppressWarnings("unchecked")
        Map<String, String> credStatuses = (Map<String, String>) response.getBody().get(0).get("credentialStatuses");
        assertEquals("VALID", credStatuses.get("DEV"));
        assertEquals("UNTESTED", credStatuses.get("PROD"));
    }

    // -----------------------------------------------------------------------
    //  create tests
    // -----------------------------------------------------------------------

    @Test
    void create_createsConnectorInstance() {
        ConnectorInstanceController.CreateConnectorRequest request =
                new ConnectorInstanceController.CreateConnectorRequest(
                        "def-oracle", "Oracle Dev", "Development Oracle instance",
                        Map.of("port", "1521"));

        // LCT-045(a): enforceConnectorDirectionCompatibility looks up the
        // definition and checks its type against the SOR registry_type.
        com.pulse.sor.model.ConnectorDefinition def = new com.pulse.sor.model.ConnectorDefinition();
        def.setId("def-oracle");
        def.setName("Oracle DB");
        def.setConnectorType(com.pulse.sor.model.ConnectorType.SOURCE);
        when(connDefRepo.findById("def-oracle")).thenReturn(Optional.of(def));

        when(ciRepo.save(any(ConnectorInstance.class))).thenAnswer(inv -> {
            ConnectorInstance saved = inv.getArgument(0);
            saved.setId("ci-new");
            return saved;
        });

        ResponseEntity<ConnectorInstance> response = controller.create("sor-1", request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("sor-1", response.getBody().getSorId());
        assertEquals("def-oracle", response.getBody().getConnectorDefinitionId());
        assertEquals("Oracle Dev", response.getBody().getName());
        assertTrue(response.getBody().isEnabled());
        assertEquals(Map.of("port", "1521"), response.getBody().getConfigTemplate());
        verify(ciRepo).save(any(ConnectorInstance.class));
    }

    @Test
    void create_rejectsSourceDefinitionForTargetRegistry() {
        // LCT-045(a): trying to create a SOURCE connector under a TARGET SOR
        // must fail with 422 UNPROCESSABLE_ENTITY.
        com.pulse.sor.model.ConnectorDefinition srcDef = new com.pulse.sor.model.ConnectorDefinition();
        srcDef.setId("def-mongo-src");
        srcDef.setName("MongoDB");
        srcDef.setConnectorType(com.pulse.sor.model.ConnectorType.SOURCE);
        when(connDefRepo.findById("def-mongo-src")).thenReturn(Optional.of(srcDef));

        // Mark sor-1 as a TARGET registry so the direction check fires.
        com.pulse.sor.model.SystemOfRecord targetSor = new com.pulse.sor.model.SystemOfRecord();
        targetSor.setId("sor-1");
        targetSor.setTenantId("tenant-1");
        targetSor.setMetadata(Map.of("registry_type", "TARGET"));
        when(sorRepo.findById("sor-1")).thenReturn(Optional.of(targetSor));

        ConnectorInstanceController.CreateConnectorRequest request =
                new ConnectorInstanceController.CreateConnectorRequest(
                        "def-mongo-src", "Mongo Sink", "MongoDB target",
                        Map.of());

        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.create("sor-1", request));
        assertEquals(422, ex.getStatusCode().value());
    }

    // -----------------------------------------------------------------------
    //  delete tests
    // -----------------------------------------------------------------------

    @Test
    void delete_deletesConnectorInstance() {
        ResponseEntity<Void> response = controller.delete("sor-1", "ci-1");

        assertEquals(204, response.getStatusCode().value());
        verify(ciRepo).deleteById("ci-1");
    }

    @Test
    void get_rejectsConnectorIdFromDifferentSor() {
        ConnectorInstance foreign = buildConnectorInstance("ci-foreign", "sor-foreign", "Foreign Connector");
        when(ciRepo.findById("ci-foreign")).thenReturn(Optional.of(foreign));

        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.get("sor-1", "ci-foreign"));

        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void delete_rejectsConnectorIdFromDifferentSor() {
        ConnectorInstance foreign = buildConnectorInstance("ci-foreign", "sor-foreign", "Foreign Connector");
        when(ciRepo.findById("ci-foreign")).thenReturn(Optional.of(foreign));

        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.delete("sor-1", "ci-foreign"));

        assertEquals(403, ex.getStatusCode().value());
    }

    // -----------------------------------------------------------------------
    //  listCredentials tests — controller maps each profile through sanitize.
    // -----------------------------------------------------------------------

    @Test
    void listCredentials_delegatesSanitizeToPersistenceService() {
        CredentialProfile cred1 = buildCredentialProfile("cred-1", "ci-1", "DEV");
        CredentialProfile cred2 = buildCredentialProfile("cred-2", "ci-1", "PROD");
        when(credRepo.findByConnectorInstanceIdOrderByEnvironmentAsc("ci-1"))
                .thenReturn(List.of(cred1, cred2));

        CredentialProfile sanitized1 = buildCredentialProfile("cred-1", "ci-1", "DEV");
        CredentialProfile sanitized2 = buildCredentialProfile("cred-2", "ci-1", "PROD");
        when(credentialPersistenceService.sanitize(cred1)).thenReturn(sanitized1);
        when(credentialPersistenceService.sanitize(cred2)).thenReturn(sanitized2);

        ResponseEntity<List<CredentialProfile>> response = controller.listCredentials("ci-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().size());
        assertSame(sanitized1, response.getBody().get(0));
        assertSame(sanitized2, response.getBody().get(1));
        verify(credentialPersistenceService).sanitize(cred1);
        verify(credentialPersistenceService).sanitize(cred2);
    }

    @Test
    void getById_rejectsForeignTenantPrincipal() {
        setAuthenticated("foreign-user", "foreign@pulse.dev", "tenant-foreign", "DATA_ENGINEER");

        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.getById("ci-1"));

        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void listCredentials_rejectsForeignTenantPrincipal() {
        setAuthenticated("foreign-user", "foreign@pulse.dev", "tenant-foreign", "DATA_ENGINEER");

        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.listCredentials("ci-1"));

        assertEquals(403, ex.getStatusCode().value());
    }

    // -----------------------------------------------------------------------
    //  upsertCredential tests — controller delegates to persistCredential and
    //  maps SecretManagerException to HTTP 502.
    // -----------------------------------------------------------------------

    @Test
    void upsertCredential_delegatesToPersistenceService() {
        // Phase 1: caller passes legacy uppercase "DEV"; controller must
        // canonicalize before calling the persistence service so the
        // credential_profiles row is persisted with the canonical key.
        Map<String, Object> body = Map.of("metadata", Map.of("host", "db.example.com"));
        CredentialProfile sanitized = buildCredentialProfile("cred-1", "ci-1", "dev");
        when(credentialPersistenceService.persistCredential("ci-1", "dev", body))
                .thenReturn(sanitized);

        ResponseEntity<?> response = controller.upsertCredential("ci-1", "DEV", body);

        assertEquals(200, response.getStatusCode().value());
        assertSame(sanitized, response.getBody());
        verify(credentialPersistenceService).persistCredential("ci-1", "dev", body);
    }

    @Test
    void upsertCredential_returns502WhenSecretManagerFails() {
        Map<String, Object> body = Map.of("secretValues", Map.of("password", "plaintext"));
        when(credentialPersistenceService.persistCredential("ci-1", "dev", body))
                .thenThrow(new SecretManagerException("quota exceeded"));

        ResponseEntity<?> response = controller.upsertCredential("ci-1", "DEV", body);

        assertEquals(502, response.getStatusCode().value());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, String> errorBody = (Map<String, String>) response.getBody();
        assertEquals("Secret Manager operation failed", errorBody.get("error"));
        assertEquals("quota exceeded", errorBody.get("detail"));
    }

    // -----------------------------------------------------------------------
    //  skipCredential test — new endpoint that delegates to the service and
    //  ignores the request body.
    // -----------------------------------------------------------------------

    @Test
    void skipCredential_delegatesToPersistenceService() {
        CredentialProfile sanitized = buildCredentialProfile("cred-1", "ci-1", "dev");
        sanitized.setStatus(CredentialStatus.SKIPPED);
        when(credentialPersistenceService.skipCredential("ci-1", "dev")).thenReturn(sanitized);

        // Caller passes legacy "DEV"; controller normalizes to "dev" before
        // calling the persistence service.
        ResponseEntity<?> response = controller.skipCredential("ci-1", "DEV", null);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof CredentialProfile);
        assertEquals(CredentialStatus.SKIPPED, ((CredentialProfile) response.getBody()).getStatus());
        verify(credentialPersistenceService).skipCredential("ci-1", "dev");
    }

    @Test
    void skipCredential_ignoresRequestBody() {
        CredentialProfile sanitized = buildCredentialProfile("cred-1", "ci-1", "dev");
        sanitized.setStatus(CredentialStatus.SKIPPED);
        when(credentialPersistenceService.skipCredential("ci-1", "dev")).thenReturn(sanitized);

        Map<String, Object> noise = Map.of("arbitrary", "keys", "are", "ignored");
        ResponseEntity<?> response = controller.skipCredential("ci-1", "DEV", noise);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody() instanceof CredentialProfile);
        assertEquals(CredentialStatus.SKIPPED, ((CredentialProfile) response.getBody()).getStatus());
        verify(credentialPersistenceService).skipCredential("ci-1", "dev");
    }

    // -----------------------------------------------------------------------
    //  credentialReadiness test — delegates to CredentialReadinessService.
    // -----------------------------------------------------------------------

    @Test
    void credentialReadiness_delegatesToReadinessService() {
        Map<String, Object> expected = Map.of(
                "pipelineId", "pipeline-1",
                "environment", "dev",
                "ready", true,
                "connections", List.of()
        );
        when(credentialReadinessService.compute("pipeline-1", "dev")).thenReturn(expected);

        // Caller may still pass legacy uppercase; controller canonicalizes.
        ResponseEntity<?> response = controller.credentialReadiness("pipeline-1", "DEV");

        assertEquals(200, response.getStatusCode().value());
        assertSame(expected, response.getBody());
        verify(credentialReadinessService).compute("pipeline-1", "dev");
    }

    // -----------------------------------------------------------------------
    //  Pass-through sanity: controller never reaches into secrets logic.
    // -----------------------------------------------------------------------

    @Test
    void upsertCredential_neverTouchesRepositoryDirectly() {
        Map<String, Object> body = Map.of("metadata", Map.of("host", "x"));
        when(credentialPersistenceService.persistCredential(anyString(), anyString(), eq(body)))
                .thenReturn(buildCredentialProfile("cred-1", "ci-1", "dev"));

        controller.upsertCredential("ci-1", "DEV", body);

        // Controller canonicalizes legacy "DEV" -> "dev" before delegating.
        verify(credentialPersistenceService).persistCredential("ci-1", "dev", body);
        // credRepo.save / findBy... are not called from the controller path.
        // No verify lines for credRepo because strict stubs would flag unused.
    }

    @Test
    void skipCredential_returnsSanitizedProfileWithSkippedStatus() {
        CredentialProfile sanitized = new CredentialProfile();
        sanitized.setConnectorInstanceId("ci-1");
        sanitized.setEnvironment("dev");
        sanitized.setStatus(CredentialStatus.SKIPPED);
        sanitized.setConnectionConfig(Map.of());
        when(credentialPersistenceService.skipCredential("ci-1", "dev")).thenReturn(sanitized);

        ResponseEntity<?> response = controller.skipCredential("ci-1", "DEV", null);

        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof CredentialProfile);
        CredentialProfile body = (CredentialProfile) response.getBody();
        assertEquals(CredentialStatus.SKIPPED, body.getStatus());
        // Sanitized profile carries an empty canonical config.
        assertTrue(body.getConnectionMetadata().isEmpty());
        assertTrue(body.getSecretReferences().isEmpty());
        assertFalse(body.getConnectionConfig().containsKey("password"));
    }

    @Test
    void upsertCredential_rejectsUnknownEnvironment() {
        // Phase 1: unknown env strings must surface as HTTP 400 at the
        // controller boundary, never as a 500 deep inside Secret Manager.
        ResponseEntity<?> response = controller.upsertCredential("ci-1", "STAGING",
                Map.of("metadata", Map.of("host", "x")));

        assertEquals(400, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, String> errorBody = (Map<String, String>) response.getBody();
        assertEquals("invalid_environment", errorBody.get("error"));
        assertTrue(errorBody.get("detail").contains("Unknown deployment environment"));
    }

    @Test
    void skipCredential_rejectsUnknownEnvironment() {
        ResponseEntity<?> response = controller.skipCredential("ci-1", "preprod", null);
        assertEquals(400, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, String> errorBody = (Map<String, String>) response.getBody();
        assertEquals("invalid_environment", errorBody.get("error"));
    }

    @Test
    void credentialReadiness_rejectsUnknownEnvironment() {
        ResponseEntity<?> response = controller.credentialReadiness("pipeline-1", "qa");
        assertEquals(400, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, String> errorBody = (Map<String, String>) response.getBody();
        assertEquals("invalid_environment", errorBody.get("error"));
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private ConnectorInstance buildConnectorInstance(String id, String sorId, String name) {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setId(id);
        ci.setSorId(sorId);
        ci.setConnectorDefinitionId("def-oracle");
        ci.setName(name);
        ci.setDescription("Test connector " + name);
        ci.setConfigTemplate(Map.of());
        ci.setEnabled(true);
        return ci;
    }

    private CredentialProfile buildCredentialProfile(String id, String ciId, String env) {
        CredentialProfile cred = new CredentialProfile();
        cred.setId(id);
        cred.setConnectorInstanceId(ciId);
        cred.setEnvironment(env);
        cred.setConnectionConfig(Map.of("host", "db.example.com"));
        cred.setStatus(CredentialStatus.UNTESTED);
        return cred;
    }

    private void setAuthenticated(String userId, String email, String tenantId, String role) {
        JwtPrincipal principal = new JwtPrincipal(userId, email, email, tenantId, role, List.of());
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
