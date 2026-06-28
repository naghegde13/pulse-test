package com.pulse.broker.trust;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PeerPulseTrustBindingRepository extends JpaRepository<PeerPulseTrustBinding, String> {
    List<PeerPulseTrustBinding> findByLocalTenantIdOrderByCreatedAtDesc(String localTenantId);
    List<PeerPulseTrustBinding> findByStatus(String status);
    Optional<PeerPulseTrustBinding> findFirstByFederatedTenantKeyAndEnvironmentAndInvokerPersonaAndTargetOwnerPersona(
            String federatedTenantKey, String environment, String invokerPersona, String targetOwnerPersona);
}
