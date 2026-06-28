package com.pulse.pipeline.repository;

import com.pulse.pipeline.model.PortWiring;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PortWiringRepository extends JpaRepository<PortWiring, String> {

    List<PortWiring> findByVersionIdOrderByCreatedAtAsc(String versionId);

    List<PortWiring> findByVersionIdAndTargetInstanceId(String versionId, String targetInstanceId);

    void deleteByVersionIdAndId(String versionId, String id);

    void deleteByVersionId(String versionId);
}
