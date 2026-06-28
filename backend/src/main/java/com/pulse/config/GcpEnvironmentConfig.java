package com.pulse.config;

import com.pulse.deploy.environment.DeploymentEnvironment;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "pulse.gcp")
public class GcpEnvironmentConfig {

    private String secretManagerMode = "local-stub";
    private String localStubKey = "pulse-dev-local-stub-key-do-not-use-in-prod";
    private Map<String, EnvironmentProject> environmentProjects = new LinkedHashMap<>();

    public String getSecretManagerMode() { return secretManagerMode; }
    public void setSecretManagerMode(String secretManagerMode) { this.secretManagerMode = secretManagerMode; }

    public String getLocalStubKey() { return localStubKey; }
    public void setLocalStubKey(String localStubKey) { this.localStubKey = localStubKey; }

    public Map<String, EnvironmentProject> getEnvironmentProjects() { return environmentProjects; }
    public void setEnvironmentProjects(Map<String, EnvironmentProject> environmentProjects) {
        this.environmentProjects = environmentProjects == null ? new LinkedHashMap<>() : environmentProjects;
    }

    /**
     * Resolve a configured GCP project id for an environment string. Accepts
     * canonical lowercase keys ({@code dev}, {@code integration}, ...) and
     * legacy uppercase aliases ({@code DEV}, {@code INT}, {@code PRODUCTION},
     * ...). Property keys defined in {@code application.yml} under
     * {@code pulse.gcp.environment-projects} may use either case for backward
     * compatibility — this method resolves the canonical form first, then
     * falls back to the legacy uppercase form, then case-insensitively.
     */
    public String resolveProjectId(String environment) {
        if (environment == null) {
            throw new IllegalArgumentException("Environment is required to resolve GCP project id");
        }
        DeploymentEnvironment canonical;
        try {
            canonical = DeploymentEnvironment.parse(environment);
        } catch (IllegalArgumentException badEnv) {
            throw new IllegalArgumentException(
                    "No GCP project configured for environment: " + environment
                    + " (" + badEnv.getMessage() + ")");
        }
        EnvironmentProject ep = lookup(canonical);
        if (ep == null) {
            throw new IllegalArgumentException(
                    "No GCP project configured for environment: " + canonical.key()
                    + ". Configure pulse.gcp.environment-projects." + canonical.key()
                    + ".project-id (legacy " + canonical.name() + " key also accepted).");
        }
        return ep.getProjectId();
    }

    private EnvironmentProject lookup(DeploymentEnvironment canonical) {
        // Try every alias the canonical env accepts, in priority order:
        //   1. canonical lowercase ("dev", "integration", ...)
        //   2. canonical uppercase enum name ("DEV", "INTEGRATION", ...)
        //   3. extra legacy YAML aliases (e.g. "PRODUCTION" for PROD,
        //      "INT" for INTEGRATION) so older application.yml files keep
        //      working unchanged during the Phase 1 transition.
        for (String alias : aliasesFor(canonical)) {
            EnvironmentProject ep = environmentProjects.get(alias);
            if (ep != null) {
                return ep;
            }
        }
        // Final pass: case-insensitive scan against every accepted alias so
        // operators can use mixed-case YAML keys like `Dev` or `Production`.
        java.util.Set<String> acceptedLowercase = new java.util.LinkedHashSet<>();
        for (String alias : aliasesFor(canonical)) {
            acceptedLowercase.add(alias.toLowerCase(Locale.ROOT));
        }
        for (var entry : environmentProjects.entrySet()) {
            String key = entry.getKey();
            if (key == null) continue;
            if (acceptedLowercase.contains(key.toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static java.util.List<String> aliasesFor(DeploymentEnvironment canonical) {
        return switch (canonical) {
            case LOCAL       -> java.util.List.of("local",       "LOCAL");
            case DEV         -> java.util.List.of("dev",         "DEV");
            case INTEGRATION -> java.util.List.of("integration", "INTEGRATION", "INT");
            case UAT         -> java.util.List.of("uat",         "UAT");
            case PROD        -> java.util.List.of("prod",        "PROD", "PRODUCTION");
        };
    }

    public static class EnvironmentProject {
        private String projectId;
        private String region;

        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
    }
}
