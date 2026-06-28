package com.pulse.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "pulse.runtime")
public class RuntimeAuthorityProperties {

    private String activePersona;
    private String secretAuthority = "GCP_SECRET_MANAGER";
    private boolean allowLocalMaterialization = true;
    private String legalRuntimeMatrixVersion = "arch-004.v1";
    private List<String> allowedTargetTypes = List.of();
    private List<String> allowedStorageBackends = List.of();
    private List<String> allowedOrchestrators = List.of();
    private List<String> allowedComputeRuntimes = List.of();
    private List<String> allowedStorageKinds = List.of();
    private List<String> allowedCatalogs = List.of();
    private List<String> allowedBrokerPeers = List.of();
    private Map<String, List<String>> allowedMaterializations = Map.of();

    public String getActivePersona() { return activePersona; }
    public void setActivePersona(String activePersona) { this.activePersona = activePersona; }

    public String getSecretAuthority() { return secretAuthority; }
    public void setSecretAuthority(String secretAuthority) { this.secretAuthority = secretAuthority; }

    public boolean isAllowLocalMaterialization() { return allowLocalMaterialization; }
    public void setAllowLocalMaterialization(boolean allowLocalMaterialization) {
        this.allowLocalMaterialization = allowLocalMaterialization;
    }

    public String getLegalRuntimeMatrixVersion() { return legalRuntimeMatrixVersion; }
    public void setLegalRuntimeMatrixVersion(String legalRuntimeMatrixVersion) {
        this.legalRuntimeMatrixVersion = legalRuntimeMatrixVersion;
    }

    public List<String> getAllowedTargetTypes() { return allowedTargetTypes; }
    public void setAllowedTargetTypes(List<String> allowedTargetTypes) {
        this.allowedTargetTypes = allowedTargetTypes;
    }

    public List<String> getAllowedStorageBackends() { return allowedStorageBackends; }
    public void setAllowedStorageBackends(List<String> allowedStorageBackends) {
        this.allowedStorageBackends = allowedStorageBackends;
    }

    public List<String> getAllowedOrchestrators() { return allowedOrchestrators; }
    public void setAllowedOrchestrators(List<String> allowedOrchestrators) {
        this.allowedOrchestrators = allowedOrchestrators;
    }

    public List<String> getAllowedComputeRuntimes() { return allowedComputeRuntimes; }
    public void setAllowedComputeRuntimes(List<String> allowedComputeRuntimes) {
        this.allowedComputeRuntimes = allowedComputeRuntimes;
    }

    public List<String> getAllowedStorageKinds() { return allowedStorageKinds; }
    public void setAllowedStorageKinds(List<String> allowedStorageKinds) {
        this.allowedStorageKinds = allowedStorageKinds;
    }

    public List<String> getAllowedCatalogs() { return allowedCatalogs; }
    public void setAllowedCatalogs(List<String> allowedCatalogs) {
        this.allowedCatalogs = allowedCatalogs;
    }

    public List<String> getAllowedBrokerPeers() { return allowedBrokerPeers; }
    public void setAllowedBrokerPeers(List<String> allowedBrokerPeers) {
        this.allowedBrokerPeers = allowedBrokerPeers;
    }

    public Map<String, List<String>> getAllowedMaterializations() { return allowedMaterializations; }
    public void setAllowedMaterializations(Map<String, List<String>> allowedMaterializations) {
        this.allowedMaterializations = allowedMaterializations;
    }
}
