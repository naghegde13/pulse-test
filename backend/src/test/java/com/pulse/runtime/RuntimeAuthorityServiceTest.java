package com.pulse.runtime;

import com.pulse.runtime.config.RuntimeAuthorityProperties;
import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.runtime.service.RuntimeAuthorityService.RuntimeAuthorityViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeAuthorityServiceTest {

    private RuntimeAuthorityService gcpService;
    private RuntimeAuthorityService dpcService;

    @BeforeEach
    void setUp() {
        RuntimeAuthorityProperties gcpProps = new RuntimeAuthorityProperties();
        gcpProps.setActivePersona("GCP_PULSE");
        gcpService = new RuntimeAuthorityService(gcpProps);
        gcpService.initialize();

        RuntimeAuthorityProperties dpcProps = new RuntimeAuthorityProperties();
        dpcProps.setActivePersona("DPC_PULSE");
        dpcService = new RuntimeAuthorityService(dpcProps);
        dpcService.initialize();
    }

    @Test
    @DisplayName("GCP allows GCP_COMPOSER_DATAPROC target type")
    void gcpAllowsGcpTarget() {
        assertTrue(gcpService.isTargetTypeAllowed("GCP_COMPOSER_DATAPROC"));
    }

    @Test
    @DisplayName("GCP denies DPC_AIRFLOW_OPENSHIFT_SPARK target type")
    void gcpDeniesDpcTarget() {
        assertFalse(gcpService.isTargetTypeAllowed("DPC_AIRFLOW_OPENSHIFT_SPARK"));
    }

    @Test
    @DisplayName("DPC allows DPC_AIRFLOW_OPENSHIFT_SPARK target type")
    void dpcAllowsDpcTarget() {
        assertTrue(dpcService.isTargetTypeAllowed("DPC_AIRFLOW_OPENSHIFT_SPARK"));
    }

    @Test
    @DisplayName("DPC denies GCP_COMPOSER_DATAPROC target type")
    void dpcDeniesGcpTarget() {
        assertFalse(dpcService.isTargetTypeAllowed("GCP_COMPOSER_DATAPROC"));
    }

    @Test
    @DisplayName("LOCAL_MATERIALIZATION is allowed when flag is true")
    void localMaterializationAllowed() {
        assertTrue(gcpService.isTargetTypeAllowed("LOCAL_MATERIALIZATION"));
    }

    @Test
    @DisplayName("GCP allows GCP storage backend")
    void gcpAllowsGcpBackend() {
        assertTrue(gcpService.isStorageBackendAllowed("GCP"));
    }

    @Test
    @DisplayName("GCP denies DPC storage backend")
    void gcpDeniesDpcBackend() {
        assertFalse(gcpService.isStorageBackendAllowed("DPC"));
    }

    @Test
    @DisplayName("DPC allows DPC storage backend")
    void dpcAllowsDpcBackend() {
        assertTrue(dpcService.isStorageBackendAllowed("DPC"));
    }

    @Test
    @DisplayName("DPC denies GCP storage backend")
    void dpcDeniesGcpBackend() {
        assertFalse(dpcService.isStorageBackendAllowed("GCP"));
    }

    @Test
    @DisplayName("GCP allows iceberg_bq_managed at bronze")
    void gcpAllowsIcebergAtBronze() {
        assertTrue(gcpService.isMaterializationAllowed("bronze", "iceberg_bq_managed"));
    }

    @Test
    @DisplayName("GCP allows bq_native at gold")
    void gcpAllowsBqNativeAtGold() {
        assertTrue(gcpService.isMaterializationAllowed("gold", "bq_native"));
    }

    @Test
    @DisplayName("GCP denies parquet at gold")
    void gcpDeniesParquetAtGold() {
        assertFalse(gcpService.isMaterializationAllowed("gold", "parquet"));
    }

    @Test
    @DisplayName("DPC allows parquet at all layers")
    void dpcAllowsParquetAtAllLayers() {
        assertTrue(dpcService.isMaterializationAllowed("bronze", "parquet"));
        assertTrue(dpcService.isMaterializationAllowed("silver", "parquet"));
        assertTrue(dpcService.isMaterializationAllowed("gold", "parquet"));
    }

    @Test
    @DisplayName("DPC denies bq_native at any layer")
    void dpcDeniesBqNative() {
        assertFalse(dpcService.isMaterializationAllowed("bronze", "bq_native"));
        assertFalse(dpcService.isMaterializationAllowed("gold", "bq_native"));
    }

    @Test
    @DisplayName("validateTargetType throws for illegal target")
    void validateTargetTypeThrows() {
        assertThrows(RuntimeAuthorityViolationException.class,
                () -> gcpService.validateTargetType("DPC_AIRFLOW_OPENSHIFT_SPARK"));
    }

    @Test
    @DisplayName("validateStorageBackend throws for illegal backend")
    void validateStorageBackendThrows() {
        assertThrows(RuntimeAuthorityViolationException.class,
                () -> gcpService.validateStorageBackend("DPC"));
    }

    @Test
    @DisplayName("validateMaterialization throws for illegal format at layer")
    void validateMaterializationThrows() {
        assertThrows(RuntimeAuthorityViolationException.class,
                () -> gcpService.validateMaterialization("gold", "parquet"));
    }

    @Test
    @DisplayName("RuntimeCapabilityMatrix convergence: GCP decisions match authority")
    void gcpCapabilityMatrixConvergence() {
        var auth = gcpService.getAuthority();
        assertTrue(auth.isTargetTypeAllowed("GCP_COMPOSER_DATAPROC"));
        assertFalse(auth.isTargetTypeAllowed("DPC_AIRFLOW_OPENSHIFT_SPARK"));
        assertTrue(auth.isMaterializationAllowed("bronze", "iceberg_bq_managed"));
        assertTrue(auth.isMaterializationAllowed("gold", "bq_native"));
    }

    @Test
    @DisplayName("RuntimeCapabilityMatrix convergence: DPC decisions match authority")
    void dpcCapabilityMatrixConvergence() {
        var auth = dpcService.getAuthority();
        assertTrue(auth.isTargetTypeAllowed("DPC_AIRFLOW_OPENSHIFT_SPARK"));
        assertFalse(auth.isTargetTypeAllowed("GCP_COMPOSER_DATAPROC"));
        assertTrue(auth.isMaterializationAllowed("bronze", "parquet"));
        assertFalse(auth.isMaterializationAllowed("gold", "bq_native"));
    }

    @Test
    @DisplayName("Active persona is exactly one value, never tenant-derived")
    void activePersonaIsStable() {
        assertEquals(RuntimePersona.GCP_PULSE, gcpService.getActivePersona());
        assertEquals(RuntimePersona.DPC_PULSE, dpcService.getActivePersona());
    }
}
