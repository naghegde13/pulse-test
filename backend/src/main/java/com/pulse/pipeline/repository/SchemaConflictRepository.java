package com.pulse.pipeline.repository;

import com.pulse.pipeline.model.SchemaConflict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SchemaConflictRepository extends JpaRepository<SchemaConflict, String> {

    List<SchemaConflict> findByVersionIdOrderByCreatedAtDesc(String versionId);

    List<SchemaConflict> findByVersionIdAndResolutionStatusOrderByCreatedAtDesc(
            String versionId, String resolutionStatus);

    List<SchemaConflict> findByInstanceIdAndResolutionStatus(String instanceId, String resolutionStatus);

    void deleteByInstanceIdAndResolutionStatus(String instanceId, String resolutionStatus);
}
