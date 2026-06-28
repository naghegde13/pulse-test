package com.pulse.pipeline.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "instance_port_schemas")
public class InstancePortSchema extends BaseEntity {

    @Column(name = "instance_id", nullable = false)
    private String instanceId;

    @Column(name = "port_name", nullable = false)
    private String portName;

    @Column(nullable = false)
    private String direction;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema_json", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> schemaJson;

    @Column(name = "schema_hash", nullable = false)
    private String schemaHash = "";

    @Column(nullable = false)
    private String source = "propagated";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> override;

    @Column(columnDefinition = "TEXT")
    private String notes;

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getPortName() { return portName; }
    public void setPortName(String portName) { this.portName = portName; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public Map<String, Object> getSchemaJson() { return schemaJson; }
    public void setSchemaJson(Map<String, Object> schemaJson) { this.schemaJson = schemaJson; }
    public String getSchemaHash() { return schemaHash; }
    public void setSchemaHash(String schemaHash) { this.schemaHash = schemaHash; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Map<String, Object> getOverride() { return override; }
    public void setOverride(Map<String, Object> override) { this.override = override; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
