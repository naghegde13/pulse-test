package com.pulse.broker.trust;

import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.runtime.service.RuntimeAuthorityService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class PeerPulseTrustBindingService {

    private final PeerPulseTrustBindingRepository repository;
    private final RuntimeAuthorityService runtimeAuthority;
    private final ActorResolverService actorResolver;

    public PeerPulseTrustBindingService(PeerPulseTrustBindingRepository repository,
                                        RuntimeAuthorityService runtimeAuthority,
                                        ActorResolverService actorResolver) {
        this.repository = repository;
        this.runtimeAuthority = runtimeAuthority;
        this.actorResolver = actorResolver;
    }

    @Transactional(readOnly = true)
    public List<PeerPulseTrustBinding> list(String tenantId) {
        actorResolver.resolve(CallerSurface.UI, tenantId);
        return repository.findByLocalTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional
    public PeerPulseTrustBinding create(String tenantId, UpsertTrustBindingRequest request) {
        actorResolver.resolve(CallerSurface.UI, tenantId);
        runtimeAuthority.validateBrokerPeerAllowed(request.targetOwnerPersona());
        PeerPulseTrustBinding binding = new PeerPulseTrustBinding();
        binding.setLocalTenantId(tenantId);
        apply(binding, request);
        return repository.save(binding);
    }

    @Transactional
    public PeerPulseTrustBinding update(String id, UpsertTrustBindingRequest request) {
        PeerPulseTrustBinding binding = get(id);
        actorResolver.resolve(CallerSurface.UI, binding.getLocalTenantId());
        runtimeAuthority.validateBrokerPeerAllowed(request.targetOwnerPersona());
        apply(binding, request);
        binding.setStatus("UNVALIDATED");
        binding.setValidatedAt(null);
        binding.setValidationError(null);
        return repository.save(binding);
    }

    @Transactional
    public PeerPulseTrustBinding validateCapability(String id) {
        PeerPulseTrustBinding binding = get(id);
        actorResolver.resolve(CallerSurface.UI, binding.getLocalTenantId());
        try {
            Map<String, Object> capability = Map.of(
                    "kind", "remote_airflow_target",
                    "runtimePulseApiRequired", false,
                    "airflowBaseUrlConfigured", binding.getAirflowBaseUrl() != null && !binding.getAirflowBaseUrl().isBlank(),
                    "outboundSecretConfigured", binding.getOutboundSecretRef() != null && !binding.getOutboundSecretRef().isBlank(),
                    "targetCount", configuredTargets(binding).size());
            binding.setStatus("VALIDATED");
            binding.setValidatedAt(Instant.now());
            binding.setCapabilitySnapshot(capability);
            binding.setValidationError(null);
        } catch (RuntimeException e) {
            binding.setStatus("ERROR");
            binding.setValidationError(e.getMessage());
        }
        return repository.save(binding);
    }

    @Transactional
    public void delete(String id) {
        PeerPulseTrustBinding binding = get(id);
        actorResolver.resolve(CallerSurface.UI, binding.getLocalTenantId());
        repository.delete(binding);
    }

    public PeerPulseTrustBinding get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PeerPulseTrustBinding", id));
    }

    private static void apply(PeerPulseTrustBinding binding, UpsertTrustBindingRequest request) {
        binding.setEnvironment(normalizeEnv(request.environment()));
        binding.setInvokerPersona(required(request.invokerPersona(), "invokerPersona"));
        binding.setTargetOwnerPersona(required(request.targetOwnerPersona(), "targetOwnerPersona"));
        binding.setFederatedTenantKey(required(request.federatedTenantKey(), "federatedTenantKey"));
        binding.setAirflowBaseUrl(required(request.airflowBaseUrl(), "airflowBaseUrl"));
        binding.setIssuer(required(request.issuer(), "issuer"));
        binding.setAudience(required(request.audience(), "audience"));
        binding.setJwksUri(blankToNull(request.jwksUri()));
        binding.setInboundSharedSecretRef(blankToNull(request.inboundSharedSecretRef()));
        binding.setOutboundSecretRef(blankToNull(request.outboundSecretRef()));
        binding.setMetadata(request.metadata() == null ? Map.of() : request.metadata());
    }

    private static String normalizeEnv(String value) {
        return required(value, "environment").trim().toLowerCase();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record UpsertTrustBindingRequest(
            String environment,
            String invokerPersona,
            String targetOwnerPersona,
            String federatedTenantKey,
            String airflowBaseUrl,
            String issuer,
            String audience,
            String jwksUri,
            String inboundSharedSecretRef,
            String outboundSecretRef,
            Map<String, Object> metadata
    ) {}

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> configuredTargets(PeerPulseTrustBinding binding) {
        Object targets = binding.getMetadata() == null ? null : binding.getMetadata().get("remoteTargets");
        if (targets instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }
}
