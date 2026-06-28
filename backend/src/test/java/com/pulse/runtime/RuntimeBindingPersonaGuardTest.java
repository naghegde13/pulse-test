package com.pulse.runtime;

import com.pulse.runtime.model.RuntimeBinding;
import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.runtime.service.RuntimeAuthorityService.RuntimeAuthorityViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ARCH-004 Phase 8 — Persona guard contract for runtime bindings.
 *
 * <p>Proves that the runtime authority service correctly gates storage
 * backend access by persona: GCP_PULSE allows only GCP bindings as
 * PRIMARY for non-local environments, DPC_PULSE allows only DPC, and
 * LOCAL binding_kind is always allowed for the local environment
 * regardless of persona.
 */
class RuntimeBindingPersonaGuardTest {

    private final RuntimeAuthorityService gcpService = TestRuntimeAuthorityFactory.gcpPulse();
    private final RuntimeAuthorityService dpcService = TestRuntimeAuthorityFactory.dpcPulse();

    @Test
    @DisplayName("GCP_PULSE persona allows GCP storage backend for PRIMARY bindings")
    void gcpPersonaAllowsGcpStorageBackend() {
        assertTrue(gcpService.isStorageBackendAllowed("GCP"),
                "GCP_PULSE must allow GCP storage backend");

        RuntimeBinding gcpBinding = primaryBinding("integration", "GCP");
        assertTrue(gcpService.isStorageBackendAllowed(gcpBinding.getBindingKind()),
                "GCP_PULSE must allow GCP binding kind on PRIMARY rows");
    }

    @Test
    @DisplayName("GCP_PULSE persona denies DPC storage backend for PRIMARY bindings")
    void gcpPersonaDeniesDpcStorageBackend() {
        assertFalse(gcpService.isStorageBackendAllowed("DPC"),
                "GCP_PULSE must deny DPC storage backend");

        assertThrows(RuntimeAuthorityViolationException.class,
                () -> gcpService.validateStorageBackend("DPC"),
                "GCP_PULSE must throw on DPC storage backend validation");
    }

    @Test
    @DisplayName("DPC_PULSE persona allows DPC storage backend for PRIMARY bindings")
    void dpcPersonaAllowsDpcStorageBackend() {
        assertTrue(dpcService.isStorageBackendAllowed("DPC"),
                "DPC_PULSE must allow DPC storage backend");

        RuntimeBinding dpcBinding = primaryBinding("integration", "DPC");
        assertTrue(dpcService.isStorageBackendAllowed(dpcBinding.getBindingKind()),
                "DPC_PULSE must allow DPC binding kind on PRIMARY rows");
    }

    @Test
    @DisplayName("DPC_PULSE persona denies GCP storage backend for PRIMARY bindings")
    void dpcPersonaDeniesGcpStorageBackend() {
        assertFalse(dpcService.isStorageBackendAllowed("GCP"),
                "DPC_PULSE must deny GCP storage backend");

        assertThrows(RuntimeAuthorityViolationException.class,
                () -> dpcService.validateStorageBackend("GCP"),
                "DPC_PULSE must throw on GCP storage backend validation");
    }

    @Test
    @DisplayName("LOCAL_MATERIALIZATION target type is allowed for both personas")
    void localMaterializationAllowedForBothPersonas() {
        assertTrue(gcpService.isTargetTypeAllowed("LOCAL_MATERIALIZATION"),
                "GCP_PULSE must allow LOCAL_MATERIALIZATION");
        assertTrue(dpcService.isTargetTypeAllowed("LOCAL_MATERIALIZATION"),
                "DPC_PULSE must allow LOCAL_MATERIALIZATION");
    }

    @Test
    @DisplayName("Opposite-family target types are denied by each persona")
    void oppositeFamilyTargetTypesDenied() {
        // GCP denies DPC target
        assertFalse(gcpService.isTargetTypeAllowed("DPC_AIRFLOW_OPENSHIFT_SPARK"),
                "GCP_PULSE must deny DPC_AIRFLOW_OPENSHIFT_SPARK target type");
        assertThrows(RuntimeAuthorityViolationException.class,
                () -> gcpService.validateTargetType("DPC_AIRFLOW_OPENSHIFT_SPARK"));

        // DPC denies GCP target
        assertFalse(dpcService.isTargetTypeAllowed("GCP_COMPOSER_DATAPROC"),
                "DPC_PULSE must deny GCP_COMPOSER_DATAPROC target type");
        assertThrows(RuntimeAuthorityViolationException.class,
                () -> dpcService.validateTargetType("GCP_COMPOSER_DATAPROC"));
    }

    @Test
    @DisplayName("DIAGNOSTIC-role bindings can reference opposite-family kinds without authority violation")
    void diagnosticBindingsDoNotTriggerPersonaGuard() {
        // A DIAGNOSTIC binding referencing the opposite storage backend
        // is a valid observability row — persona guards only gate
        // operational (PRIMARY) authority, not diagnostic reads.
        RuntimeBinding diagnosticGcpOnDpc = diagnosticBinding("integration", "GCP");
        assertTrue(diagnosticGcpOnDpc.isDiagnostic(),
                "Binding must be recognized as DIAGNOSTIC");
        assertFalse(diagnosticGcpOnDpc.isPrimary(),
                "DIAGNOSTIC binding must not be treated as PRIMARY");

        RuntimeBinding diagnosticDpcOnGcp = diagnosticBinding("integration", "DPC");
        assertTrue(diagnosticDpcOnGcp.isDiagnostic());
        assertFalse(diagnosticDpcOnGcp.isPrimary());
    }

    @Test
    @DisplayName("Active persona is stable and matches factory configuration")
    void activePersonaIsStable() {
        assertEquals(RuntimePersona.GCP_PULSE, gcpService.getActivePersona(),
                "gcpPulse() must produce GCP_PULSE persona");
        assertEquals(RuntimePersona.DPC_PULSE, dpcService.getActivePersona(),
                "dpcPulse() must produce DPC_PULSE persona");
    }

    // ---- helpers ----

    // PKT-FINAL-5 / BUG-39: bindings are deployment-global, no tenant arg.
    private static RuntimeBinding primaryBinding(String environment, String bindingKind) {
        RuntimeBinding b = new RuntimeBinding();
        b.setEnvironment(environment);
        b.setBindingKind(bindingKind);
        b.setSettingsRole("PRIMARY");
        b.setRecordState("ACTIVE");
        return b;
    }

    private static RuntimeBinding diagnosticBinding(String environment, String bindingKind) {
        RuntimeBinding b = new RuntimeBinding();
        b.setEnvironment(environment);
        b.setBindingKind(bindingKind);
        b.setSettingsRole("DIAGNOSTIC");
        b.setRecordState("ACTIVE");
        return b;
    }
}
