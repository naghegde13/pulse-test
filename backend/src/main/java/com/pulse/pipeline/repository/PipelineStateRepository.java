package com.pulse.pipeline.repository;

import com.pulse.pipeline.model.PipelineState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PipelineStateRepository extends JpaRepository<PipelineState, String> {

    Optional<PipelineState> findByPipelineIdAndInstanceIdAndStateKey(
            String pipelineId, String instanceId, String stateKey);

    List<PipelineState> findByPipelineIdAndInstanceIdOrderByStateKeyAsc(
            String pipelineId, String instanceId);

    void deleteByPipelineIdAndInstanceIdAndStateKey(
            String pipelineId, String instanceId, String stateKey);
}
