package com.pulse.storage.contract.repository;

import com.pulse.storage.contract.model.TableContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TableContractRepository extends JpaRepository<TableContract, String> {

    List<TableContract> findByVersionIdAndStatus(String versionId, String status);

    List<TableContract> findByVersionIdAndProducingInstanceIdAndStatus(
            String versionId, String producingInstanceId, String status);

    Optional<TableContract> findByVersionIdAndProducingInstanceIdAndOutputPortNameAndStatus(
            String versionId, String producingInstanceId, String outputPortName, String status);

    List<TableContract> findByVersionIdOrderByLayerAscTableSlugAsc(String versionId);
}
