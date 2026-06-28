package com.pulse.broker.mirror;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RemoteTargetRuntimeMirrorRepository extends JpaRepository<RemoteTargetRuntimeMirror, String> {
    Optional<RemoteTargetRuntimeMirror> findFirstByFederatedTenantKeyAndRemoteTargetRefAndEnvironment(
            String federatedTenantKey, String remoteTargetRef, String environment);
}
