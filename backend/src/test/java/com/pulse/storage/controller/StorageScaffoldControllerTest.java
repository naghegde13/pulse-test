package com.pulse.storage.controller;

import com.pulse.storage.service.StorageScaffoldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * PKT-0012 — StorageScaffoldController unit tests.
 */
@ExtendWith(MockitoExtension.class)
class StorageScaffoldControllerTest {

    private static final String TENANT_ID = "tenant-acme-lending";

    @Mock private StorageScaffoldService scaffoldService;

    private StorageScaffoldController controller;

    @BeforeEach
    void setUp() {
        controller = new StorageScaffoldController(scaffoldService);
    }

    @Test
    void preview_success_returns200() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "previewed");
        when(scaffoldService.preview(TENANT_ID)).thenReturn(result);

        var response = controller.preview(TENANT_ID);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("previewed", response.getBody().get("status"));
    }

    @Test
    void preview_failed_returns422() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "failed");
        result.put("error", "No tenant GCP config found");
        when(scaffoldService.preview(TENANT_ID)).thenReturn(result);

        var response = controller.preview(TENANT_ID);

        assertEquals(422, response.getStatusCode().value());
        assertEquals("failed", response.getBody().get("status"));
    }

    @Test
    void execute_blocked_returns409() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "operator_blocked");
        result.put("error", "Live GCS writes are not authorized");
        when(scaffoldService.execute(TENANT_ID)).thenReturn(result);

        var response = controller.execute(TENANT_ID);

        assertEquals(409, response.getStatusCode().value());
        assertEquals("operator_blocked", response.getBody().get("status"));
    }

    @Test
    void execute_failed_returns422() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "failed");
        result.put("error", "Some error");
        when(scaffoldService.execute(TENANT_ID)).thenReturn(result);

        var response = controller.execute(TENANT_ID);

        assertEquals(422, response.getStatusCode().value());
    }

    @Test
    void execute_partial_returns207() {
        // PKT-FINAL-5: when service reports a partial failure (some folder
        // markers created, others failed) the controller surfaces 207
        // Multi-Status so the UI can distinguish full vs partial success.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "partial");
        result.put("httpStatus", 207);
        result.put("created", 12);
        result.put("failed", 1);
        when(scaffoldService.execute(TENANT_ID)).thenReturn(result);

        var response = controller.execute(TENANT_ID);

        assertEquals(207, response.getStatusCode().value());
        assertEquals("partial", response.getBody().get("status"));
    }

    @Test
    void execute_success_returns200() {
        // PKT-FINAL-5: when all paths succeed the service reports httpStatus=200
        // explicitly. Controller respects the service-provided code.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "executed");
        result.put("httpStatus", 200);
        result.put("created", 13);
        when(scaffoldService.execute(TENANT_ID)).thenReturn(result);

        var response = controller.execute(TENANT_ID);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("executed", response.getBody().get("status"));
    }

    @Test
    void status_returns200() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "previewed");
        when(scaffoldService.getStatus(TENANT_ID)).thenReturn(result);

        var response = controller.status(TENANT_ID);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("previewed", response.getBody().get("status"));
    }
}
