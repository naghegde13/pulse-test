package com.pulse.pipeline.repository;

import com.pulse.pipeline.model.InstancePortSchema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InstancePortSchemaRepository extends JpaRepository<InstancePortSchema, String> {

    List<InstancePortSchema> findByInstanceId(String instanceId);

    List<InstancePortSchema> findByInstanceIdIn(List<String> instanceIds);

    Optional<InstancePortSchema> findByInstanceIdAndPortNameAndDirection(
            String instanceId, String portName, String direction);
}
