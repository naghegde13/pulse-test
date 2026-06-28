package com.pulse.runtime;

import com.pulse.runtime.config.RuntimeAuthorityProperties;
import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.service.RuntimeAuthorityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeAuthorityPropertiesTest {

    @Test
    @DisplayName("GCP_PULSE preset binds correctly from config properties")
    void gcpPresetBindsCorrectly() {
        RuntimeAuthorityProperties props = new RuntimeAuthorityProperties();
        props.setActivePersona("GCP_PULSE");
        props.setSecretAuthority("GCP_SECRET_MANAGER");

        RuntimeAuthorityService service = new RuntimeAuthorityService(props);
        service.initialize();

        var auth = service.getAuthority();
        assertEquals(RuntimePersona.GCP_PULSE, auth.activePersona());
        assertEquals("GCP Pulse", auth.displayName());
        assertTrue(auth.allowedTargetTypes().contains("GCP_COMPOSER_DATAPROC"));
        assertTrue(auth.allowedStorageBackends().contains("GCP"));
        assertTrue(auth.allowedCatalogs().contains("BIGQUERY"));
        assertTrue(auth.isMaterializationAllowed("gold", "bq_native"));
        assertFalse(auth.isMaterializationAllowed("gold", "parquet"));
    }

    @Test
    @DisplayName("DPC_PULSE preset binds correctly from config properties")
    void dpcPresetBindsCorrectly() {
        RuntimeAuthorityProperties props = new RuntimeAuthorityProperties();
        props.setActivePersona("DPC_PULSE");
        props.setSecretAuthority("GCP_SECRET_MANAGER");

        RuntimeAuthorityService service = new RuntimeAuthorityService(props);
        service.initialize();

        var auth = service.getAuthority();
        assertEquals(RuntimePersona.DPC_PULSE, auth.activePersona());
        assertEquals("DPC Pulse", auth.displayName());
        assertTrue(auth.allowedTargetTypes().contains("DPC_AIRFLOW_OPENSHIFT_SPARK"));
        assertTrue(auth.allowedStorageBackends().contains("DPC"));
        assertTrue(auth.allowedCatalogs().contains("HIVE_JDBC"));
        assertTrue(auth.isMaterializationAllowed("bronze", "parquet"));
        assertFalse(auth.isMaterializationAllowed("gold", "bq_native"));
    }

    @Test
    @DisplayName("Explicit config overrides presets")
    void explicitConfigOverridesPreset() {
        RuntimeAuthorityProperties props = new RuntimeAuthorityProperties();
        props.setActivePersona("GCP_PULSE");
        props.setSecretAuthority("GCP_SECRET_MANAGER");
        props.setAllowedTargetTypes(List.of("CUSTOM_TARGET"));
        props.setAllowedStorageBackends(List.of("CUSTOM_STORAGE"));
        props.setAllowedOrchestrators(List.of("CUSTOM_ORCH"));
        props.setAllowedComputeRuntimes(List.of("CUSTOM_COMPUTE"));
        props.setAllowedStorageKinds(List.of("CUSTOM_KIND"));
        props.setAllowedCatalogs(List.of("CUSTOM_CATALOG"));
        props.setAllowedMaterializations(Map.of("bronze", List.of("parquet")));

        RuntimeAuthorityService service = new RuntimeAuthorityService(props);
        service.initialize();

        var auth = service.getAuthority();
        assertTrue(auth.allowedTargetTypes().contains("CUSTOM_TARGET"));
        assertFalse(auth.allowedTargetTypes().contains("GCP_COMPOSER_DATAPROC"));
    }

    @Test
    @DisplayName("Invalid persona throws on initialization")
    void invalidPersonaThrows() {
        RuntimeAuthorityProperties props = new RuntimeAuthorityProperties();
        props.setActivePersona("UNKNOWN_PERSONA");

        RuntimeAuthorityService service = new RuntimeAuthorityService(props);
        assertThrows(IllegalArgumentException.class, service::initialize);
    }

    @Test
    @DisplayName("Null persona defaults to GCP_PULSE with warning")
    void nullPersonaDefaultsToGcp() {
        RuntimeAuthorityProperties props = new RuntimeAuthorityProperties();
        props.setActivePersona(null);

        RuntimeAuthorityService service = new RuntimeAuthorityService(props);
        service.initialize();

        assertEquals(RuntimePersona.GCP_PULSE, service.getActivePersona());
    }

    @Test
    @DisplayName("DPC persona can use GCP_SECRET_MANAGER")
    void dpcCanUseGcpSecretManager() {
        RuntimeAuthorityProperties props = new RuntimeAuthorityProperties();
        props.setActivePersona("DPC_PULSE");
        props.setSecretAuthority("GCP_SECRET_MANAGER");

        RuntimeAuthorityService service = new RuntimeAuthorityService(props);
        service.initialize();

        var auth = service.getAuthority();
        assertEquals(RuntimePersona.DPC_PULSE, auth.activePersona());
        assertEquals(com.pulse.runtime.model.SecretAuthorityKind.GCP_SECRET_MANAGER, auth.secretAuthority());
    }
}
