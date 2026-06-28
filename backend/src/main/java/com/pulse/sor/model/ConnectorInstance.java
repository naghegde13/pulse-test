package com.pulse.sor.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "connector_instances")
public class ConnectorInstance extends BaseEntity {

    @Column(name = "sor_id", nullable = false)
    private String sorId;

    @Column(name = "connector_definition_id", nullable = false)
    private String connectorDefinitionId;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_template", columnDefinition = "jsonb")
    private Map<String, Object> configTemplate;

    @Column(nullable = false)
    private boolean enabled = true;

    public String getSorId() { return sorId; }
    public void setSorId(String sorId) { this.sorId = sorId; }
    public String getConnectorDefinitionId() { return connectorDefinitionId; }
    public void setConnectorDefinitionId(String connectorDefinitionId) { this.connectorDefinitionId = connectorDefinitionId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, Object> getConfigTemplate() { return configTemplate; }
    public void setConfigTemplate(Map<String, Object> configTemplate) { this.configTemplate = configTemplate; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
