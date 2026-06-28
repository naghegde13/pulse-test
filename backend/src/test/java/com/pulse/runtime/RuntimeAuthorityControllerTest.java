package com.pulse.runtime;

import com.pulse.runtime.config.RuntimeAuthorityProperties;
import com.pulse.runtime.controller.RuntimeAuthorityController;
import com.pulse.runtime.service.RuntimeAuthorityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeAuthorityControllerTest {

    private RuntimeAuthorityController controller;

    @BeforeEach
    void setUp() {
        RuntimeAuthorityProperties props = new RuntimeAuthorityProperties();
        props.setActivePersona("GCP_PULSE");
        RuntimeAuthorityService service = new RuntimeAuthorityService(props);
        service.initialize();
        controller = new RuntimeAuthorityController(service);
    }

    @Test
    @DisplayName("GET returns active persona and complete matrix")
    void returnsActivePersonaAndMatrix() {
        ResponseEntity<Map<String, Object>> response = controller.getAuthority();
        assertEquals(200, response.getStatusCode().value());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("GCP_PULSE", body.get("activePersona"));
        assertEquals("GCP Pulse", body.get("displayName"));
        assertNotNull(body.get("allowedTargetTypes"));
        assertNotNull(body.get("allowedStorageBackends"));
        assertNotNull(body.get("allowedMaterializations"));
        assertEquals("GCP_SECRET_MANAGER", body.get("secretAuthority"));
        assertEquals("arch-004.v1", body.get("legalRuntimeMatrixVersion"));
    }

    @Test
    @DisplayName("DPC persona returns DPC-specific matrix")
    void dpcPersonaReturnsCorrectMatrix() {
        RuntimeAuthorityProperties dpcProps = new RuntimeAuthorityProperties();
        dpcProps.setActivePersona("DPC_PULSE");
        RuntimeAuthorityService dpcService = new RuntimeAuthorityService(dpcProps);
        dpcService.initialize();
        RuntimeAuthorityController dpcController = new RuntimeAuthorityController(dpcService);

        ResponseEntity<Map<String, Object>> response = dpcController.getAuthority();
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("DPC_PULSE", body.get("activePersona"));
        assertEquals("DPC Pulse", body.get("displayName"));
    }
}
