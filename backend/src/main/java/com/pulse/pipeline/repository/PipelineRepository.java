package com.pulse.pipeline.repository;

import com.pulse.pipeline.model.Pipeline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PipelineRepository extends JpaRepository<Pipeline, String> {

    List<Pipeline> findByTenantIdOrderByUpdatedAtDesc(String tenantId);

    List<Pipeline> findByTenantIdAndDomainNameOrderByUpdatedAtDesc(String tenantId, String domainName);

    List<Pipeline> findByTenantIdAndDomainIdOrderByUpdatedAtDesc(String tenantId, String domainId);
}
