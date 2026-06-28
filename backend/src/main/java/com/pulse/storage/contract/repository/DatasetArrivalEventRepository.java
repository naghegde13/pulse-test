package com.pulse.storage.contract.repository;

import com.pulse.storage.contract.model.DatasetArrivalEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DatasetArrivalEventRepository extends JpaRepository<DatasetArrivalEvent, String> {

    List<DatasetArrivalEvent> findByDatasetIdOrderByCreatedAtDesc(String datasetId);

    List<DatasetArrivalEvent> findByLandingContractIdOrderByCreatedAtDesc(String landingContractId);

    Optional<DatasetArrivalEvent> findByLandingContractIdAndIngestDateAndArrivalId(
            String landingContractId, String ingestDate, String arrivalId);

    Optional<DatasetArrivalEvent> findFirstByLandingContractIdOrderByCreatedAtAsc(
            String landingContractId);
}
