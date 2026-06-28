package com.pulse.pipeline.service;

import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.storage.StorageBackendValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlueprintInstanceConfigurationServiceTest {

    @Mock private PipelineRepository pipelineRepo;
    private StorageBackendValidator validator;
    private BlueprintInstanceConfigurationService service;

    @BeforeEach
    void setUp() {
        validator = new StorageBackendValidator();
        service = new BlueprintInstanceConfigurationService(validator, pipelineRepo);
        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipe-1");
        pipeline.setTenantId("t1");
        pipeline.setDefaultStorageBackend("DPC");
        lenient().when(pipelineRepo.findById("pipe-1")).thenReturn(Optional.of(pipeline));
    }

    // -----------------------------------------------------------------------
    // resolveForAdd
    // -----------------------------------------------------------------------

    @Test
    void resolveForAdd_explicitTopLevel_winsAndStripsLegacyParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("storage_backend", "GCP");  // mirrored legacy key
        params.put("lake_layer", "bronze");
        params.put("lake_format", "iceberg_bq_managed");
        params.put("other", "preserved");

        BlueprintInstanceConfigurationService.Resolution r =
                service.resolveForAdd("pipe-1", "DPC", "silver", "parquet", params);

        assertEquals("DPC", r.storageBackend(), "top-level beats params");
        assertEquals("silver", r.lakeLayer());
        assertEquals("parquet", r.lakeFormat());
        assertFalse(r.sanitizedParams().containsKey("storage_backend"));
        assertFalse(r.sanitizedParams().containsKey("lake_layer"));
        assertFalse(r.sanitizedParams().containsKey("lake_format"));
        assertEquals("preserved", r.sanitizedParams().get("other"));
        assertTrue(r.deprecations().containsAll(java.util.List.of("storage_backend", "lake_layer", "lake_format")));
    }

    @Test
    void resolveForAdd_paramsLegacyOnly_promotedToCanonical() {
        Map<String, Object> params = new HashMap<>();
        params.put("storage_backend", "GCP");
        params.put("lake_layer", "bronze");
        params.put("lake_format", "iceberg_external");

        BlueprintInstanceConfigurationService.Resolution r =
                service.resolveForAdd("pipe-1", null, null, null, params);

        assertEquals("GCP", r.storageBackend());
        assertEquals("bronze", r.lakeLayer());
        assertEquals("iceberg_external", r.lakeFormat());
        assertTrue(r.sanitizedParams().isEmpty());
    }

    @Test
    void resolveForAdd_missingStorageBackend_fillsFromPipelineDefault() {
        BlueprintInstanceConfigurationService.Resolution r =
                service.resolveForAdd("pipe-1", null, null, null, Map.of("k", "v"));

        assertEquals("DPC", r.storageBackend(), "pipeline default applies");
        assertNull(r.lakeLayer());
        assertNull(r.lakeFormat());
        assertEquals("v", r.sanitizedParams().get("k"));
    }

    @Test
    void resolveForAdd_goldOnGcpWithoutBqNative_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.resolveForAdd("pipe-1", "GCP", "gold", "iceberg_external", Map.of()));
        assertTrue(ex.getMessage().toLowerCase().contains("bq_native"),
                "matrix violation should mention bq_native rule, got: " + ex.getMessage());
    }

    @Test
    void resolveForAdd_layerWithoutFormat_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveForAdd("pipe-1", "DPC", "silver", null, Map.of()));
    }

    // -----------------------------------------------------------------------
    // resolveForUpdate
    // -----------------------------------------------------------------------

    @Test
    void resolveForUpdate_omittedFieldsPreserve() {
        SubPipelineInstance current = new SubPipelineInstance();
        current.setPipelineId("pipe-1");
        current.setStorageBackend("GCP");
        current.setLakeLayer("bronze");
        current.setLakeFormat("iceberg_bq_managed");

        BlueprintInstanceConfigurationService.Resolution r =
                service.resolveForUpdate(current, null, null, null, Map.of("foo", "bar"));

        assertEquals("GCP", r.storageBackend());
        assertEquals("bronze", r.lakeLayer());
        assertEquals("iceberg_bq_managed", r.lakeFormat());
        assertEquals("bar", r.sanitizedParams().get("foo"));
    }

    @Test
    void resolveForUpdate_explicitTopLevelOverridesCurrent() {
        SubPipelineInstance current = new SubPipelineInstance();
        current.setPipelineId("pipe-1");
        current.setStorageBackend("GCP");
        current.setLakeLayer("bronze");
        current.setLakeFormat("delta");

        BlueprintInstanceConfigurationService.Resolution r =
                service.resolveForUpdate(current, "DPC", "silver", "parquet", Map.of());

        assertEquals("DPC", r.storageBackend());
        assertEquals("silver", r.lakeLayer());
        assertEquals("parquet", r.lakeFormat());
    }
}
