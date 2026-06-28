package com.pulse.broker.mirror;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RemoteTargetCatalogMirrorRepository extends JpaRepository<RemoteTargetCatalogMirror, String> {
    Optional<RemoteTargetCatalogMirror> findFirstByFederatedTenantKeyAndRemoteTargetRef(
            String federatedTenantKey, String remoteTargetRef);
    List<RemoteTargetCatalogMirror> findByFederatedTenantKeyAndStatus(String federatedTenantKey, String status);
}
