package com.pulse.sor.repository;

import com.pulse.sor.model.AsofAdvanceLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AsofAdvanceLogRepository extends JpaRepository<AsofAdvanceLog, String> {
    List<AsofAdvanceLog> findByDatasetIdOrderByCreatedAtDesc(String datasetId);
}
