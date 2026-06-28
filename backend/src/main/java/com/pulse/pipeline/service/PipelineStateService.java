package com.pulse.pipeline.service;

import com.pulse.pipeline.model.PipelineState;
import com.pulse.pipeline.repository.PipelineStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PipelineStateService {

    private final PipelineStateRepository repository;

    public PipelineStateService(PipelineStateRepository repository) {
        this.repository = repository;
    }

    public Optional<PipelineState> get(String pipelineId, String instanceId, String stateKey) {
        return repository.findByPipelineIdAndInstanceIdAndStateKey(pipelineId, instanceId, stateKey);
    }

    public List<PipelineState> listByInstance(String pipelineId, String instanceId) {
        return repository.findByPipelineIdAndInstanceIdOrderByStateKeyAsc(pipelineId, instanceId);
    }

    @Transactional
    public PipelineState upsert(String pipelineId, String instanceId, String stateKey,
                                Map<String, Object> stateValue) {
        PipelineState entity = repository
                .findByPipelineIdAndInstanceIdAndStateKey(pipelineId, instanceId, stateKey)
                .orElseGet(() -> {
                    PipelineState fresh = new PipelineState();
                    fresh.setPipelineId(pipelineId);
                    fresh.setInstanceId(instanceId);
                    fresh.setStateKey(stateKey);
                    return fresh;
                });
        entity.setStateValue(stateValue != null ? stateValue : Map.of());
        return repository.save(entity);
    }

    @Transactional
    public boolean delete(String pipelineId, String instanceId, String stateKey) {
        Optional<PipelineState> existing = repository
                .findByPipelineIdAndInstanceIdAndStateKey(pipelineId, instanceId, stateKey);
        if (existing.isEmpty()) {
            return false;
        }
        repository.delete(existing.get());
        return true;
    }
}
