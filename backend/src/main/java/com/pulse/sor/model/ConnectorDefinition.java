package com.pulse.sor.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

@Entity
@Table(name = "connector_definitions")
public class ConnectorDefinition extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "connector_type", nullable = false)
    private ConnectorType connectorType;

    @Column(name = "docker_repository", nullable = false)
    private String dockerRepository;

    @Column(name = "docker_image_tag", nullable = false)
    private String dockerImageTag;

    @Column(name = "icon_url")
    private String iconUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "connection_spec", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> connectionSpec;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supported_modes", columnDefinition = "jsonb")
    private List<String> supportedModes;

    @Column(name = "documentation_url")
    private String documentationUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_stage", nullable = false)
    private ReleaseStage releaseStage;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_strategy", nullable = false)
    private ConnectorCredentialStrategy credentialStrategy = ConnectorCredentialStrategy.CONNECTOR_SPECIFIC;

    /**
     * LCT-045/048: declared sink-writer family for DESTINATION connectors.
     * Null for SOURCE-only definitions.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "sink_writer_kind")
    private SinkWriterKind sinkWriterKind;

    /**
     * LCT-048: legal write dispositions for this connector family, ordered with
     * the default first. Each entry carries {@code value} (the blueprint-legal
     * write-mode token) and {@code label} (connector-family-appropriate copy).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "write_dispositions", columnDefinition = "jsonb")
    private List<Map<String, Object>> writeDispositions;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ConnectorType getConnectorType() { return connectorType; }
    public void setConnectorType(ConnectorType connectorType) { this.connectorType = connectorType; }
    public String getDockerRepository() { return dockerRepository; }
    public void setDockerRepository(String dockerRepository) { this.dockerRepository = dockerRepository; }
    public String getDockerImageTag() { return dockerImageTag; }
    public void setDockerImageTag(String dockerImageTag) { this.dockerImageTag = dockerImageTag; }
    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }
    public Map<String, Object> getConnectionSpec() { return connectionSpec; }
    public void setConnectionSpec(Map<String, Object> connectionSpec) { this.connectionSpec = connectionSpec; }
    public List<String> getSupportedModes() { return supportedModes; }
    public void setSupportedModes(List<String> supportedModes) { this.supportedModes = supportedModes; }
    public String getDocumentationUrl() { return documentationUrl; }
    public void setDocumentationUrl(String documentationUrl) { this.documentationUrl = documentationUrl; }
    public ReleaseStage getReleaseStage() { return releaseStage; }
    public void setReleaseStage(ReleaseStage releaseStage) { this.releaseStage = releaseStage; }
    public ConnectorCredentialStrategy getCredentialStrategy() { return credentialStrategy; }
    public void setCredentialStrategy(ConnectorCredentialStrategy credentialStrategy) { this.credentialStrategy = credentialStrategy; }
    public SinkWriterKind getSinkWriterKind() { return sinkWriterKind; }
    public void setSinkWriterKind(SinkWriterKind sinkWriterKind) { this.sinkWriterKind = sinkWriterKind; }
    public List<Map<String, Object>> getWriteDispositions() { return writeDispositions; }
    public void setWriteDispositions(List<Map<String, Object>> writeDispositions) { this.writeDispositions = writeDispositions; }
}
