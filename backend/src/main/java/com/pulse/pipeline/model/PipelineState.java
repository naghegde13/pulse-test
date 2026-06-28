package com.pulse.pipeline.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "pipeline_state")
public class PipelineState extends BaseEntity {

    @Column(name = "pipeline_id", nullable = false)
    private String pipelineId;

    @Column(name = "instance_id", nullable = false)
    private String instanceId;

    @Column(name = "state_key", nullable = false)
    private String stateKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_value", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> stateValue;

    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getStateKey() { return stateKey; }
    public void setStateKey(String stateKey) { this.stateKey = stateKey; }
    public Map<String, Object> getStateValue() { return stateValue; }
    public void setStateValue(Map<String, Object> stateValue) { this.stateValue = stateValue; }
}
