package com.pulse.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot smoke test: confirms that profile=dev (set by scripts/gcp-deploy.sh) flips
 * pulse.gcp.secret-manager-mode off "local-stub" so Cloud Run hits real Secret Manager,
 * and that the configured DEV project resolves to the actual deploy target.
 */
class GcpEnvironmentConfigProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(GcpEnvironmentConfig.class);

    @Test
    void devProfileEnablesRealSecretManagerAndResolvesPulseProject() {
        contextRunner
                .withSystemProperties("spring.profiles.active=dev")
                .run(ctx -> {
                    GcpEnvironmentConfig cfg = ctx.getBean(GcpEnvironmentConfig.class);
                    assertThat(cfg.getSecretManagerMode())
                            .as("dev profile must flip secret-manager-mode off local-stub")
                            .isEqualTo("gcp-secret-manager");
                    assertThat(cfg.resolveProjectId("DEV"))
                            .as("dev profile must resolve DEV → actual GCP project")
                            .isEqualTo("pulse-489421");
                });
    }

    @Test
    void defaultProfileKeepsLocalStubForBootRun() {
        contextRunner.run(ctx -> {
            GcpEnvironmentConfig cfg = ctx.getBean(GcpEnvironmentConfig.class);
            assertThat(cfg.getSecretManagerMode())
                    .as("default profile (no profile active) must stay on local-stub for bootRun")
                    .isEqualTo("local-stub");
        });
    }
}
