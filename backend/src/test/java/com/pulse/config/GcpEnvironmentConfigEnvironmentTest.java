package com.pulse.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 contract test for {@link GcpEnvironmentConfig#resolveProjectId(String)}:
 * canonical lowercase keys MUST resolve, legacy uppercase YAML keys MUST
 * keep working (backward compatibility for {@code application-dev.yml} which
 * still defines {@code pulse.gcp.environment-projects.DEV}), and unknown
 * environments MUST fail with a clear actionable message.
 */
class GcpEnvironmentConfigEnvironmentTest {

    @Test
    void resolvesCanonicalLowercaseKeys() {
        GcpEnvironmentConfig cfg = new GcpEnvironmentConfig();
        cfg.setEnvironmentProjects(Map.of(
                "local",       project("pulse-local"),
                "dev",         project("pulse-dev"),
                "integration", project("pulse-int"),
                "uat",         project("pulse-uat"),
                "prod",        project("pulse-prod")
        ));

        assertThat(cfg.resolveProjectId("local")).isEqualTo("pulse-local");
        assertThat(cfg.resolveProjectId("dev")).isEqualTo("pulse-dev");
        assertThat(cfg.resolveProjectId("integration")).isEqualTo("pulse-int");
        assertThat(cfg.resolveProjectId("uat")).isEqualTo("pulse-uat");
        assertThat(cfg.resolveProjectId("prod")).isEqualTo("pulse-prod");
    }

    @ParameterizedTest(name = "[{index}] config key \"{0}\", input \"{1}\" -> \"{2}\"")
    @CsvSource({
            // Canonical YAML keys accept canonical and legacy inputs.
            "dev,         dev,         pulse-canonical-dev",
            "dev,         DEV,         pulse-canonical-dev",
            "prod,        prod,        pulse-canonical-prod",
            "prod,        PROD,        pulse-canonical-prod",
            "prod,        PRODUCTION,  pulse-canonical-prod",
            "integration, integration, pulse-canonical-int",
            "integration, INT,         pulse-canonical-int",
            "integration, INTEGRATION, pulse-canonical-int",
            // Legacy uppercase YAML key (existing application-dev.yml) still
            // resolves both canonical and legacy inputs.
            "DEV,         dev,         pulse-legacy-dev",
            "DEV,         DEV,         pulse-legacy-dev",
            "INTEGRATION, integration, pulse-legacy-int",
            "INTEGRATION, INT,         pulse-legacy-int",
            "PRODUCTION,  prod,        pulse-legacy-prod",
            "PRODUCTION,  PRODUCTION,  pulse-legacy-prod"
    })
    void resolvesAcrossYamlAndInputCasing(String yamlKey, String input, String expectedProjectId) {
        GcpEnvironmentConfig cfg = new GcpEnvironmentConfig();
        Map<String, GcpEnvironmentConfig.EnvironmentProject> projects = new LinkedHashMap<>();
        projects.put(yamlKey, project(expectedProjectId));
        cfg.setEnvironmentProjects(projects);

        assertThat(cfg.resolveProjectId(input)).isEqualTo(expectedProjectId);
    }

    @Test
    void unknownEnvironmentFailsWithActionableMessage() {
        GcpEnvironmentConfig cfg = new GcpEnvironmentConfig();
        cfg.setEnvironmentProjects(Map.of("DEV", project("pulse-489421")));

        // A genuinely unsupported env (not in the canonical or legacy alias
        // set) must fail at the canonical-parse boundary.
        assertThatThrownBy(() -> cfg.resolveProjectId("STAGING"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STAGING")
                .hasMessageContaining("No GCP project configured for environment");

        // A canonical env that has no configured project must fail with a
        // hint that includes the canonical key the operator should add.
        assertThatThrownBy(() -> cfg.resolveProjectId("uat"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("environment: uat")
                .hasMessageContaining("pulse.gcp.environment-projects.uat");

        assertThatThrownBy(() -> cfg.resolveProjectId(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Environment is required");
    }

    @Test
    void emptyEnvironmentProjectsAlwaysFailsCleanly() {
        GcpEnvironmentConfig cfg = new GcpEnvironmentConfig();
        // No environment-projects at all (e.g. local bootRun without dev profile).
        assertThatThrownBy(() -> cfg.resolveProjectId("dev"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No GCP project configured for environment");
    }

    private GcpEnvironmentConfig.EnvironmentProject project(String projectId) {
        GcpEnvironmentConfig.EnvironmentProject ep = new GcpEnvironmentConfig.EnvironmentProject();
        ep.setProjectId(projectId);
        ep.setRegion("us-central1");
        return ep;
    }
}
