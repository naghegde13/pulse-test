package com.pulse.sor.controller;

import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.AuthorizationPolicyService;
import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.policy.PulseRole;
import com.pulse.secret.service.CredentialPersistenceService;
import com.pulse.secret.service.CredentialReadinessService;
import com.pulse.secret.service.CredentialValidationService;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 enforcement contract — SECRET_METADATA gate fires before
 * the credential persistence service touches Secret Manager, both for
 * UI-driven controller calls and for AGENT-driven chat-tool calls.
 */
class ConnectorInstanceControllerAuthorizationTest {

    private ConnectorInstanceRepository ciRepo;
    private CredentialProfileRepository credRepo;
    private ConnectorDefinitionRepository connDefRepo;
    private DatasetRepository datasetRepo;
    private CredentialPersistenceService credentialPersistenceService;
    private CredentialReadinessService credentialReadinessService;
    private SystemOfRecordRepository sorRepo;
    private AuthorizationPolicyService policy;
    private ActorResolverService actorResolver;
    private ConnectorInstanceController controller;

    @BeforeEach
    void setUp() {
        ciRepo = mock(ConnectorInstanceRepository.class);
        credRepo = mock(CredentialProfileRepository.class);
        connDefRepo = mock(ConnectorDefinitionRepository.class);
        datasetRepo = mock(DatasetRepository.class);
        credentialPersistenceService = mock(CredentialPersistenceService.class);
        credentialReadinessService = mock(CredentialReadinessService.class);
        sorRepo = mock(SystemOfRecordRepository.class);
        policy = new AuthorizationPolicyService();
        actorResolver = spy(new ActorResolverService());
        controller = new ConnectorInstanceController(
                ciRepo, credRepo, connDefRepo, datasetRepo,
                credentialPersistenceService, credentialReadinessService,
                mock(CredentialValidationService.class),
                sorRepo, policy, actorResolver);

        ConnectorInstance ci = new ConnectorInstance();
        ci.setId("ci-1");
        ci.setSorId("sor-1");
        when(ciRepo.findById("ci-1")).thenReturn(Optional.of(ci));
        SystemOfRecord sor = new SystemOfRecord();
        sor.setId("sor-1");
        sor.setTenantId("tenant-A");
        when(sorRepo.findById("sor-1")).thenReturn(Optional.of(sor));
    }

    @Test
    @DisplayName("upsertCredential denied for PIPELINE_DEVELOPER → 403 missing_role and no Secret Manager call")
    void upsertCredentialDeniedForPipelineDeveloper() {
        when(actorResolver.resolve(CallerSurface.UI, "tenant-A")).thenReturn(
                new CallerContext("user-test", "tenant-A",
                        Set.of(PulseRole.PIPELINE_DEVELOPER), CallerSurface.UI));

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> controller.upsertCredential("ci-1", "DEV",
                        Map.of("metadata", Map.of("host", "x"))));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("missing_role", denied.getReason());
        verify(credentialPersistenceService, never())
                .persistCredential(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("skipCredential denied for cross-tenant caller → 403 tenant_membership")
    void skipCredentialDeniedAcrossTenants() {
        when(actorResolver.resolve(CallerSurface.UI, "tenant-A")).thenReturn(
                new CallerContext("user-test", "tenant-B",
                        Set.of(PulseRole.TENANT_ADMIN), CallerSurface.UI));

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> controller.skipCredential("ci-1", "DEV", null));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("tenant_membership", denied.getReason());
        verify(credentialPersistenceService, never()).skipCredential(anyString(), anyString());
    }

    @Test
    @DisplayName("upsertCredential allowed for TENANT_ADMIN delegates to persistence service")
    void upsertCredentialAllowedForTenantAdmin() {
        when(actorResolver.resolve(CallerSurface.UI, "tenant-A")).thenReturn(
                new CallerContext("user-test", "tenant-A",
                        Set.of(PulseRole.TENANT_ADMIN), CallerSurface.UI));
        when(credentialPersistenceService.persistCredential(anyString(), anyString(), any()))
                .thenReturn(new com.pulse.sor.model.CredentialProfile());

        ResponseEntity<?> response = controller.upsertCredential("ci-1", "DEV",
                Map.of("metadata", Map.of("host", "x")));
        assertEquals(200, response.getStatusCode().value());
        verify(credentialPersistenceService).persistCredential("ci-1", "dev",
                Map.of("metadata", Map.of("host", "x")));
    }
}
