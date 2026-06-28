package com.pulse.sor.repository;

import com.pulse.sor.model.CredentialProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CredentialProfileRepository extends JpaRepository<CredentialProfile, String> {
    List<CredentialProfile> findByConnectorInstanceIdOrderByEnvironmentAsc(String connectorInstanceId);
    Optional<CredentialProfile> findByConnectorInstanceIdAndEnvironment(String connectorInstanceId, String environment);
}
