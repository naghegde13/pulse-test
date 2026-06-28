package com.pulse.broker.trust;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class PeerPulseTrustBindingController {

    private final PeerPulseTrustBindingService service;

    public PeerPulseTrustBindingController(PeerPulseTrustBindingService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/tenants/{tenantId}/broker/trust-bindings")
    public ResponseEntity<List<TrustBindingSummary>> list(@PathVariable String tenantId) {
        return ResponseEntity.ok(service.list(tenantId).stream()
                .map(TrustBindingSummary::from)
                .toList());
    }

    @PostMapping("/api/v1/tenants/{tenantId}/broker/trust-bindings")
    public ResponseEntity<TrustBindingSummary> create(
            @PathVariable String tenantId,
            @RequestBody PeerPulseTrustBindingService.UpsertTrustBindingRequest request) {
        return ResponseEntity.ok(TrustBindingSummary.from(service.create(tenantId, request)));
    }

    @PutMapping("/api/v1/broker/trust-bindings/{id}")
    public ResponseEntity<TrustBindingSummary> update(
            @PathVariable String id,
            @RequestBody PeerPulseTrustBindingService.UpsertTrustBindingRequest request) {
        return ResponseEntity.ok(TrustBindingSummary.from(service.update(id, request)));
    }

    @PostMapping("/api/v1/broker/trust-bindings/{id}/validate")
    public ResponseEntity<TrustBindingSummary> validate(@PathVariable String id) {
        return ResponseEntity.ok(TrustBindingSummary.from(service.validateCapability(id)));
    }

    @DeleteMapping("/api/v1/broker/trust-bindings/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record TrustBindingSummary(
            String id,
            String environment,
            String invokerPersona,
            String targetOwnerPersona,
            String federatedTenantKey,
            String airflowBaseUrl,
            String issuer,
            String audience,
            String jwksUri,
            String status,
            String validatedAt,
            String validationError,
            boolean inboundSecretConfigured,
            boolean outboundSecretConfigured
    ) {
        static TrustBindingSummary from(PeerPulseTrustBinding binding) {
            return new TrustBindingSummary(
                    binding.getId(),
                    binding.getEnvironment(),
                    binding.getInvokerPersona(),
                    binding.getTargetOwnerPersona(),
                    binding.getFederatedTenantKey(),
                    binding.getAirflowBaseUrl(),
                    binding.getIssuer(),
                    binding.getAudience(),
                    binding.getJwksUri(),
                    binding.getStatus(),
                    binding.getValidatedAt() == null ? null : binding.getValidatedAt().toString(),
                    binding.getValidationError(),
                    binding.getInboundSharedSecretRef() != null && !binding.getInboundSharedSecretRef().isBlank(),
                    binding.getOutboundSecretRef() != null && !binding.getOutboundSecretRef().isBlank());
        }
    }
}
