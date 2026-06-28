package com.pulse.storage.contract.repository;

import com.pulse.storage.contract.model.DatasetLandingContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DatasetLandingContractRepository extends JpaRepository<DatasetLandingContract, String> {

    Optional<DatasetLandingContract> findByDatasetIdAndStatus(String datasetId, String status);

    List<DatasetLandingContract> findByTenantIdAndStatus(String tenantId, String status);

    List<DatasetLandingContract> findByDomainIdAndStatus(String domainId, String status);
}
