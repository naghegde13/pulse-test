package com.pulse.deploy.projection.repository;

import com.pulse.deploy.projection.model.RuntimeProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RuntimeProjectionRepository extends JpaRepository<RuntimeProjection, String> {

    Optional<RuntimeProjection> findByPackageIdAndTargetIdAndEnvironmentAndStatus(
            String packageId, String targetId, String environment, String status);

    List<RuntimeProjection> findByPackageIdOrderByProjectedAtDesc(String packageId);
}
