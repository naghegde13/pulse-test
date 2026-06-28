package com.pulse.storage.repository;

import com.pulse.storage.model.StorageAuthorityConflict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StorageAuthorityConflictRepository
        extends JpaRepository<StorageAuthorityConflict, String> {

    List<StorageAuthorityConflict> findByPipelineIdAndResolvedFalse(String pipelineId);

    List<StorageAuthorityConflict> findByInstanceIdAndResolvedFalse(String instanceId);

    long countByPipelineIdAndResolvedFalse(String pipelineId);
}
