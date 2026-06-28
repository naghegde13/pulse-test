package com.pulse.broker.trust;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PeerPulseTrustBindingControllerTest {

    @Test
    void listReturnsRedactedTrustBindingSummaries() {
        PeerPulseTrustBindingService service = mock(PeerPulseTrustBindingService.class);
        PeerPulseTrustBinding binding = new PeerPulseTrustBinding();
        binding.setId("trust-1");
        binding.setLocalTenantId("tenant-1");
        binding.setEnvironment("dev");
        binding.setInvokerPersona("GCP_PULSE");
        binding.setTargetOwnerPersona("DPC_PULSE");
        binding.setFederatedTenantKey("target-a");
        binding.setAirflowBaseUrl("https://airflow.example");
        binding.setIssuer("issuer");
        binding.setAudience("audience");
        binding.setJwksUri("https://peer.example/jwks");
        binding.setInboundSharedSecretRef("secret://inbound");
        binding.setOutboundSecretRef("secret://outbound");
        binding.setStatus("VALIDATED");
        binding.setCapabilitySnapshot(Map.of("runtime", "dpc"));
        binding.setMetadata(Map.of("sensitive", "metadata"));
        when(service.list("tenant-1")).thenReturn(List.of(binding));

        PeerPulseTrustBindingController controller = new PeerPulseTrustBindingController(service);
        ResponseEntity<List<PeerPulseTrustBindingController.TrustBindingSummary>> response = controller.list("tenant-1");

        assertEquals(200, response.getStatusCode().value());
        PeerPulseTrustBindingController.TrustBindingSummary summary = assertDoesNotThrow(() -> response.getBody().get(0));
        assertEquals("trust-1", summary.id());
        assertTrue(summary.inboundSecretConfigured());
        assertTrue(summary.outboundSecretConfigured());
        assertEquals("VALIDATED", summary.status());
        assertEquals("https://airflow.example", summary.airflowBaseUrl());
    }
}
