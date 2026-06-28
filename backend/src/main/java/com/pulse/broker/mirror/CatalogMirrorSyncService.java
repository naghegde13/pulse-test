package com.pulse.broker.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.broker.trust.PeerPulseTrustBinding;
import com.pulse.broker.trust.PeerPulseTrustBindingRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

@Service
public class CatalogMirrorSyncService {

    private final PeerPulseTrustBindingRepository trustBindings;
    private final RemoteTargetCatalogMirrorRepository catalogMirror;
    private final RemoteTargetRuntimeMirrorRepository runtimeMirror;
    private final ObjectMapper mapper;

    public CatalogMirrorSyncService(PeerPulseTrustBindingRepository trustBindings,
                                    RemoteTargetCatalogMirrorRepository catalogMirror,
                                    RemoteTargetRuntimeMirrorRepository runtimeMirror,
                                    ObjectMapper mapper) {
        this.trustBindings = trustBindings;
        this.catalogMirror = catalogMirror;
        this.runtimeMirror = runtimeMirror;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${pulse.broker.mirror-refresh-seconds:300}000")
    @Transactional
    public void scheduledSync() {
        trustBindings.findByStatus("VALIDATED").forEach(binding -> syncBinding(binding, false));
    }

    @Transactional
    public MirrorSyncResult syncNow(String tenantId, String environment) {
        List<PeerPulseTrustBinding> bindings = trustBindings.findByLocalTenantIdOrderByCreatedAtDesc(tenantId)
                .stream()
                .filter(binding -> "VALIDATED".equals(binding.getStatus()))
                .filter(binding -> environment == null || environment.equalsIgnoreCase(binding.getEnvironment()))
                .toList();
        int targets = 0;
        for (PeerPulseTrustBinding binding : bindings) {
            targets += syncBinding(binding, true);
        }
        return new MirrorSyncResult(bindings.size(), targets);
    }

    @SuppressWarnings("unchecked")
    private int syncBinding(PeerPulseTrustBinding binding, boolean includeRuntime) {
        Object configured = binding.getMetadata() == null ? null : binding.getMetadata().get("remoteTargets");
        List<Map<String, Object>> targets = configured instanceof List<?> list
                ? list.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> (Map<String, Object>) item)
                        .toList()
                : List.of();
        Instant now = Instant.now();
        for (Map<String, Object> target : targets) {
            String ref = asString(target.get("remoteTargetRef"), asString(target.get("id"), null));
            if (ref == null) continue;
            RemoteTargetCatalogMirror row = catalogMirror
                    .findFirstByFederatedTenantKeyAndRemoteTargetRef(binding.getFederatedTenantKey(), ref)
                    .orElseGet(RemoteTargetCatalogMirror::new);
            row.setTrustBindingId(binding.getId());
            row.setLocalTenantId(binding.getLocalTenantId());
            row.setFederatedTenantKey(binding.getFederatedTenantKey());
            row.setRemoteTargetRef(ref);
            row.setSlug(asString(target.get("slug"), ref));
            row.setDisplayName(asString(target.get("displayName"), row.getSlug()));
            row.setDescription(asString(target.get("description"), null));
            row.setStatus(asString(target.get("status"), "ACTIVE"));
            row.setLastSyncedAt(now);
            row.setPayload(target);
            row.setSignedResponseJwsSha256(sha256(mapper.valueToTree(target).toString()));
            catalogMirror.save(row);
            if (includeRuntime) syncRuntime(binding, ref, target, now);
        }
        return targets.size();
    }

    @SuppressWarnings("unchecked")
    private void syncRuntime(PeerPulseTrustBinding binding, String targetRef, Map<String, Object> target, Instant now) {
        Map<String, Object> runtime = target.get("runtime") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : target;
        RemoteTargetRuntimeMirror row = runtimeMirror
                .findFirstByFederatedTenantKeyAndRemoteTargetRefAndEnvironment(
                        binding.getFederatedTenantKey(), targetRef, binding.getEnvironment())
                .orElseGet(RemoteTargetRuntimeMirror::new);
        row.setTrustBindingId(binding.getId());
        row.setLocalTenantId(binding.getLocalTenantId());
        row.setFederatedTenantKey(binding.getFederatedTenantKey());
        row.setRemoteTargetRef(targetRef);
        row.setEnvironment(binding.getEnvironment());
        row.setPeerLogicalDagId(asString(runtime.get("remoteDagId"), asString(runtime.get("peerLogicalDagId"), targetRef)));
        row.setPayloadSchema(mapper.convertValue(runtime.getOrDefault("payloadSchema", Map.of()), Map.class));
        row.setAllowedPayloadKeys(mapper.convertValue(runtime.getOrDefault("allowedPayloadKeys", List.of()), List.class));
        row.setCompletionEvent(asString(runtime.get("completionEvent"), null));
        row.setDeploymentStatus(asString(runtime.get("deploymentStatus"), "UNKNOWN"));
        row.setLastSyncedAt(now);
        row.setPayload(runtime);
        row.setSignedResponseJwsSha256(sha256(mapper.valueToTree(runtime).toString()));
        runtimeMirror.save(row);
    }

    private static String asString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record MirrorSyncResult(int bindingsSynced, int targetsSynced) {}
}
