package com.pulse.tenant.service;

import com.pulse.auth.model.Tenant;
import com.pulse.auth.service.TenantService;
import com.pulse.command.model.CommandLog;
import com.pulse.command.model.CommandStatus;
import com.pulse.command.repository.CommandLogRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.tenant.model.TenantGcpRuntimeTopology;
import com.pulse.tenant.repository.TenantGcpRuntimeTopologyRepository;
import com.pulse.tenant.service.TenantSecretManagerBindingService.ValidationError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PKT-FINAL-5 / BUG-54 / BUG-2026-05-26-58: Unit tests for
 * {@link TenantSecretManagerBindingService}.
 *
 * <p>Covers validation, mode normalization (including the wire-format alias
 * {@code TENANT_GCP_SECRET_MANAGER}), upsert behaviour, command-log
 * journaling, and the DB→wire reverse mapping in {@code buildReadback}.
 */
@ExtendWith(MockitoExtension.class)
class TenantSecretManagerBindingServiceTest {

    private static final String TENANT_ID = "tenant-acme";

    @Mock private TenantGcpRuntimeTopologyRepository topologyRepository;
    @Mock private TenantService tenantService;
    @Mock private CommandLogRepository commandLogRepository;

    private TenantSecretManagerBindingService service;

    @BeforeEach
    void setUp() {
        service = new TenantSecretManagerBindingService(
                topologyRepository, tenantService, commandLogRepository);
        // Default: tenant exists. Individual tests override this with thenThrow
        // when they want to exercise the missing-tenant path.
        lenient().when(tenantService.getTenantEntity(any())).thenReturn(new Tenant());
        lenient().when(topologyRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------- validate(...) --------------------

    @Test
    void validate_localStubMode_passes() {
        assertTrue(service.validate("LOCAL_STUB", null).isEmpty());
        assertTrue(service.validate("LOCAL_STUB", "").isEmpty());
        assertTrue(service.validate(null, null).isEmpty()); // null mode defaults to LOCAL_STUB
    }

    @Test
    void validate_gcpModeWithProjectId_passes() {
        assertTrue(service.validate("GCP_SECRET_MANAGER", "pulse-secrets-prod").isEmpty());
        // Wire-format alias must also pass with a project id.
        assertTrue(service.validate("TENANT_GCP_SECRET_MANAGER", "pulse-secrets-prod").isEmpty());
    }

    @Test
    void validate_gcpModeWithoutProjectId_fails() {
        Optional<ValidationError> err = service.validate("GCP_SECRET_MANAGER", null);
        assertTrue(err.isPresent());
        assertEquals("MISSING_GSM_PROJECT_ID", err.get().code());

        Optional<ValidationError> err2 = service.validate("GCP_SECRET_MANAGER", "  ");
        assertTrue(err2.isPresent());
        assertEquals("MISSING_GSM_PROJECT_ID", err2.get().code());

        // Same contract for the wire-format alias.
        Optional<ValidationError> err3 = service.validate("TENANT_GCP_SECRET_MANAGER", null);
        assertTrue(err3.isPresent());
        assertEquals("MISSING_GSM_PROJECT_ID", err3.get().code());
    }

    @Test
    void validate_invalidMode_fails() {
        Optional<ValidationError> err = service.validate("HASHICORP_VAULT", null);
        assertTrue(err.isPresent());
        assertEquals("INVALID_MODE", err.get().code());
    }

    // -------------------- normalizeMode(...) --------------------

    @Test
    void normalizeMode_handlesVariants_localStub() {
        assertEquals("LOCAL_STUB", TenantSecretManagerBindingService.normalizeMode(null));
        assertEquals("LOCAL_STUB", TenantSecretManagerBindingService.normalizeMode(""));
        assertEquals("LOCAL_STUB", TenantSecretManagerBindingService.normalizeMode("LOCAL_STUB"));
        assertEquals("LOCAL_STUB", TenantSecretManagerBindingService.normalizeMode("local_stub"));
        assertEquals("LOCAL_STUB", TenantSecretManagerBindingService.normalizeMode("LOCAL-STUB"));
        assertEquals("LOCAL_STUB", TenantSecretManagerBindingService.normalizeMode("localstub"));
        assertEquals("LOCAL_STUB", TenantSecretManagerBindingService.normalizeMode("  LOCAL_STUB  "));
    }

    @Test
    void normalizeMode_handlesVariants_gcp() {
        assertEquals("GCP_SECRET_MANAGER",
                TenantSecretManagerBindingService.normalizeMode("GCP_SECRET_MANAGER"));
        assertEquals("GCP_SECRET_MANAGER",
                TenantSecretManagerBindingService.normalizeMode("gcp_secret_manager"));
        assertEquals("GCP_SECRET_MANAGER",
                TenantSecretManagerBindingService.normalizeMode("GCP-SECRET-MANAGER"));
        // Wire-format alias from the frontend panel.
        assertEquals("GCP_SECRET_MANAGER",
                TenantSecretManagerBindingService.normalizeMode("TENANT_GCP_SECRET_MANAGER"));
        assertEquals("GCP_SECRET_MANAGER",
                TenantSecretManagerBindingService.normalizeMode("tenant_gcp_secret_manager"));
        assertEquals("GCP_SECRET_MANAGER",
                TenantSecretManagerBindingService.normalizeMode("TENANT-GCP-SECRET-MANAGER"));
    }

    @Test
    void normalizeMode_handlesVariants_blocked() {
        assertEquals("BLOCKED", TenantSecretManagerBindingService.normalizeMode("BLOCKED"));
        assertEquals("BLOCKED", TenantSecretManagerBindingService.normalizeMode("blocked"));
    }

    @Test
    void normalizeMode_unknownValue_returnsNull() {
        assertNull(TenantSecretManagerBindingService.normalizeMode("HASHICORP_VAULT"));
        assertNull(TenantSecretManagerBindingService.normalizeMode("AWS_SECRETS"));
    }

    // -------------------- upsert(...) --------------------

    @Test
    void upsert_newTenant_createsRowDefaultsToLocalStub() {
        when(topologyRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

        TenantGcpRuntimeTopology saved = service.upsert(
                TENANT_ID, "actor-1", "LOCAL_STUB", null, null);

        assertNotNull(saved);
        assertEquals(TENANT_ID, saved.getTenantId());
        assertEquals("LOCAL_STUB", saved.getSecretAuthorityMode());
        verify(commandLogRepository).save(any(CommandLog.class));
    }

    @Test
    void upsert_newTenant_failsIfTenantNotFound() {
        when(tenantService.getTenantEntity(TENANT_ID))
                .thenThrow(new ResourceNotFoundException("Tenant", TENANT_ID));

        assertThrows(ResourceNotFoundException.class, () ->
                service.upsert(TENANT_ID, "actor-1", "LOCAL_STUB", null, null));
    }

    @Test
    void upsert_existingTenant_updatesModePreservesOtherFields() {
        TenantGcpRuntimeTopology existing = new TenantGcpRuntimeTopology();
        existing.setTenantId(TENANT_ID);
        existing.setSecretAuthorityMode("LOCAL_STUB");
        existing.setComposerProjectId("composer-proj"); // unrelated topology field
        existing.setBqProjectId("bq-proj");             // unrelated topology field
        when(topologyRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(existing));

        TenantGcpRuntimeTopology saved = service.upsert(
                TENANT_ID, "actor-1", "GCP_SECRET_MANAGER", "gsm-proj", "prefix-1");

        assertEquals("GCP_SECRET_MANAGER", saved.getSecretAuthorityMode());
        assertEquals("gsm-proj", saved.getSecretManagerProjectId());
        assertEquals("prefix-1", saved.getSecretNamePrefix());
        // Co-existing topology fields untouched.
        assertEquals("composer-proj", saved.getComposerProjectId());
        assertEquals("bq-proj", saved.getBqProjectId());
    }

    @Test
    void upsert_wireFormatAlias_storesCanonicalDbValue() {
        when(topologyRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

        TenantGcpRuntimeTopology saved = service.upsert(
                TENANT_ID, "actor-1", "TENANT_GCP_SECRET_MANAGER", "gsm-proj", null);

        // Stored as canonical DB form even though wire format was supplied.
        assertEquals("GCP_SECRET_MANAGER", saved.getSecretAuthorityMode());
    }

    @Test
    void upsert_gcpModeWithoutProjectId_throws() {
        // No topology stubbing required — upsert short-circuits before
        // touching the repository when the project id is missing.
        assertThrows(IllegalArgumentException.class, () ->
                service.upsert(TENANT_ID, "actor-1", "GCP_SECRET_MANAGER", null, null));
        assertThrows(IllegalArgumentException.class, () ->
                service.upsert(TENANT_ID, "actor-1", "TENANT_GCP_SECRET_MANAGER", "  ", null));
    }

    @Test
    void upsert_invalidMode_throws() {
        // Same as above — invalid mode short-circuits before repo access.
        assertThrows(IllegalArgumentException.class, () ->
                service.upsert(TENANT_ID, "actor-1", "HASHICORP_VAULT", null, null));
    }

    @Test
    void upsert_journalsCommandLogEntry() {
        when(topologyRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

        service.upsert(TENANT_ID, "actor-7", "GCP_SECRET_MANAGER", "gsm-proj", "p");

        ArgumentCaptor<CommandLog> captor = ArgumentCaptor.forClass(CommandLog.class);
        verify(commandLogRepository).save(captor.capture());
        CommandLog entry = captor.getValue();
        assertEquals("tenant.secretManager.upsert", entry.getCommandType());
        assertEquals("TENANT_SECRET_MANAGER_BINDING", entry.getAggregateType());
        assertEquals(TENANT_ID, entry.getTenantId());
        assertEquals("actor-7", entry.getActorId());
        assertEquals(CommandStatus.SUCCEEDED, entry.getStatus());
        assertNotNull(entry.getIdempotencyKey());
        assertNotNull(entry.getExecutedAt());

        Map<String, Object> payload = entry.getPayload();
        assertEquals("GCP_SECRET_MANAGER", payload.get("mode"));
        assertEquals("gsm-proj", payload.get("gsmProjectId"));
        assertEquals("p", payload.get("secretNamePrefix"));
        assertEquals(true, payload.get("changed"));
    }

    @Test
    void upsert_journalsEvenWhenStateUnchanged() {
        TenantGcpRuntimeTopology existing = new TenantGcpRuntimeTopology();
        existing.setTenantId(TENANT_ID);
        existing.setSecretAuthorityMode("LOCAL_STUB");
        when(topologyRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(existing));

        service.upsert(TENANT_ID, "actor-1", "LOCAL_STUB", null, null);

        ArgumentCaptor<CommandLog> captor = ArgumentCaptor.forClass(CommandLog.class);
        verify(commandLogRepository).save(captor.capture());
        assertEquals(false, captor.getValue().getPayload().get("changed"));
    }

    // -------------------- buildReadback(...) --------------------

    @Test
    void buildReadback_omitsSecretMaterial() {
        TenantGcpRuntimeTopology topology = new TenantGcpRuntimeTopology();
        topology.setTenantId(TENANT_ID);
        topology.setSecretAuthorityMode("GCP_SECRET_MANAGER");
        topology.setSecretManagerProjectId("gsm-proj");
        topology.setSecretNamePrefix("prefix-x");

        Map<String, Object> readback = service.buildReadback(TENANT_ID, topology);

        assertEquals(TENANT_ID, readback.get("tenantId"));
        // Contract-drift bridge: DB GCP_SECRET_MANAGER → wire TENANT_GCP_SECRET_MANAGER.
        assertEquals("TENANT_GCP_SECRET_MANAGER", readback.get("mode"));
        assertEquals("gsm-proj", readback.get("gsmProjectId"));
        assertEquals("prefix-x", readback.get("secretNamePrefix"));
        assertEquals(true, readback.get("privateKeyRedacted"));
        // No secret material in the readback.
        assertFalse(readback.containsKey("privateKey"));
        assertFalse(readback.containsKey("clientSecret"));
        assertFalse(readback.containsKey("secret"));
    }

    @Test
    void buildReadback_localStubMode_passesThroughUnchanged() {
        TenantGcpRuntimeTopology topology = new TenantGcpRuntimeTopology();
        topology.setTenantId(TENANT_ID);
        topology.setSecretAuthorityMode("LOCAL_STUB");

        Map<String, Object> readback = service.buildReadback(TENANT_ID, topology);

        assertEquals("LOCAL_STUB", readback.get("mode"));
    }

    @Test
    void buildReadback_blockedMode_passesThroughUnchanged() {
        TenantGcpRuntimeTopology topology = new TenantGcpRuntimeTopology();
        topology.setTenantId(TENANT_ID);
        topology.setSecretAuthorityMode("BLOCKED");

        Map<String, Object> readback = service.buildReadback(TENANT_ID, topology);

        assertEquals("BLOCKED", readback.get("mode"));
    }

    @Test
    void buildDefaultReadback_returnsLocalStubWithSourceMarker() {
        Map<String, Object> readback = service.buildDefaultReadback(TENANT_ID);

        assertEquals(TENANT_ID, readback.get("tenantId"));
        assertEquals("LOCAL_STUB", readback.get("mode"));
        assertNull(readback.get("gsmProjectId"));
        assertNull(readback.get("secretNamePrefix"));
        assertEquals(true, readback.get("privateKeyRedacted"));
        assertEquals("default", readback.get("source"));
    }
}
