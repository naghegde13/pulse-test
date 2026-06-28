package com.pulse.pipeline.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "port_wirings")
public class PortWiring extends BaseEntity {

    @Column(name = "version_id", nullable = false)
    private String versionId;

    @Column(name = "source_instance_id", nullable = false)
    private String sourceInstanceId;

    @Column(name = "source_port_name", nullable = false)
    private String sourcePortName;

    @Column(name = "target_instance_id", nullable = false)
    private String targetInstanceId;

    @Column(name = "target_port_name", nullable = false)
    private String targetPortName;

    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public String getSourceInstanceId() { return sourceInstanceId; }
    public void setSourceInstanceId(String sourceInstanceId) { this.sourceInstanceId = sourceInstanceId; }
    public String getSourcePortName() { return sourcePortName; }
    public void setSourcePortName(String sourcePortName) { this.sourcePortName = sourcePortName; }
    public String getTargetInstanceId() { return targetInstanceId; }
    public void setTargetInstanceId(String targetInstanceId) { this.targetInstanceId = targetInstanceId; }
    public String getTargetPortName() { return targetPortName; }
    public void setTargetPortName(String targetPortName) { this.targetPortName = targetPortName; }
}
