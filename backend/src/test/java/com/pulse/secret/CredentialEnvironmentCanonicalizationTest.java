package com.pulse.secret;

import com.pulse.config.GcpEnvironmentConfig;
import com.pulse.deploy.environment.DeploymentEnvironment;
import com.pulse.secret.service.GcpSecretManagerService;
import com.pulse.secret.service.SecretNamingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 contract test: the secret/GSM environment lookup must always
 * resolve canonical lowercase keys, regardless of whether the caller passed
 * a legacy uppercase alias (DEV, INTEGRATION, PRODUCTION, INT, PROD, LOCAL)
 * or the canonical key. Local-stub secret IDs include the canonical env
 * segment; real GSM mode drops env entirely (project id partitions per
 * env) but still rejects unknown values cleanly.
 */
class CredentialEnvironmentCanonicalizationTest {

    @Test
    @DisplayName("local-stub secret IDs use the canonical lowercase env segment for legacy aliases")
    void localStubSecretIdUsesCanonicalEnvForLegacyAliases(@TempDir Path tempDir) {
        GcpSecretManagerService svc = service("local-stub", tempDir);

        // Legacy uppercase aliases all collapse to the canonical 'dev' / 'prod'
        // / 'integration' / 'uat' / 'local' segment in the local-stub filename.
        assertThat(svc.buildSecretId(ctx("DEV", "home-lending", "los-oracle", "password", "ci-1")))
                .isEqualTo("pulse-dev-home-lending-default-source-los-oracle-password-ci-1");
        assertThat(svc.buildSecretId(ctx("PROD", "home-lending", "los-oracle", "password", "ci-1")))
                .isEqualTo("pulse-prod-home-lending-default-source-los-oracle-password-ci-1");
        assertThat(svc.buildSecretId(ctx("PRODUCTION", "home-lending", "los-oracle", "password", "ci-1")))
                .isEqualTo("pulse-prod-home-lending-default-source-los-oracle-password-ci-1");
        assertThat(svc.buildSecretId(ctx("INT", "home-lending", "los-oracle", "password", "ci-1")))
                .isEqualTo("pulse-integration-home-lending-default-source-los-oracle-password-ci-1");
        assertThat(svc.buildSecretId(ctx("INTEGRATION", "home-lending", "los-oracle", "password", "ci-1")))
                .isEqualTo("pulse-integration-home-lending-default-source-los-oracle-password-ci-1");
        assertThat(svc.buildSecretId(ctx("UAT", "home-lending", "los-oracle", "password", "ci-1")))
                .isEqualTo("pulse-uat-home-lending-default-source-los-oracle-password-ci-1");
        assertThat(svc.buildSecretId(ctx("LOCAL", "home-lending", "los-oracle", "password", "ci-1")))
                .isEqualTo("pulse-local-home-lending-default-source-los-oracle-password-ci-1");

        // Already-canonical inputs round-trip unchanged.
        assertThat(svc.buildSecretId(ctx("dev", "home-lending", "los-oracle", "password", "ci-1")))
                .isEqualTo("pulse-dev-home-lending-default-source-los-oracle-password-ci-1");
        assertThat(svc.buildSecretId(ctx("integration", "home-lending", "los-oracle", "password", "ci-1")))
                .isEqualTo("pulse-integration-home-lending-default-source-los-oracle-password-ci-1");
    }

    @Test
    @DisplayName("Real GSM mode drops env from the secret ID (project partitions per env)")
    void realGsmDropsEnvFromSecretId() {
        GcpSecretManagerService svc = service("gcp-secret-manager", null);

        // Real GSM mode never embeds env in the secret ID, regardless of the
        // input alias. The env partitioning happens via the resolved project.
        assertThat(svc.buildSecretId(ctx("DEV", "home-lending", "los-oracle", "password", "ci-1")))
                .isEqualTo("pulse-home-lending-default-source-los-oracle-password-ci-1");
        assertThat(svc.buildSecretId(ctx("PRODUCTION", "home-lending", "los-oracle", "password", "ci-1")))
                .isEqualTo("pulse-home-lending-default-source-los-oracle-password-ci-1");
    }

    @Test
    @DisplayName("buildSecretReference resolves canonical and legacy env aliases against configured projects")
    void buildSecretReferenceResolvesCanonicalAndLegacyAliases(@TempDir Path tempDir) {
        GcpEnvironmentConfig cfg = new GcpEnvironmentConfig();
        cfg.setSecretManagerMode("local-stub");
        // Keep YAML-friendly uppercase config key to prove backward
        // compatibility (existing `pulse.gcp.environment-projects.DEV` in
        // application-dev.yml stays valid).
        cfg.setEnvironmentProjects(java.util.Map.of(
                "DEV", project("pulse-489421"),
                "prod", project("pulse-prod-001")
        ));
        GcpSecretManagerService svc = new GcpSecretManagerService(cfg, tempDir.toString());

        // 'DEV' (legacy), 'dev' (canonical), and a future canonical-only
        // config (no uppercase YAML key) must all resolve cleanly.
        assertThat(svc.buildSecretReference("DEV", "secret-id-1"))
                .isEqualTo("gcp-sm://projects/pulse-489421/secrets/secret-id-1/versions/latest");
        assertThat(svc.buildSecretReference("dev", "secret-id-1"))
                .isEqualTo("gcp-sm://projects/pulse-489421/secrets/secret-id-1/versions/latest");
        assertThat(svc.buildSecretReference("PRODUCTION", "secret-id-1"))
                .isEqualTo("gcp-sm://projects/pulse-prod-001/secrets/secret-id-1/versions/latest");
        assertThat(svc.buildSecretReference("prod", "secret-id-1"))
                .isEqualTo("gcp-sm://projects/pulse-prod-001/secrets/secret-id-1/versions/latest");
    }

    @Test
    @DisplayName("Unknown environment values fail clearly at the config boundary")
    void unknownEnvironmentValuesFailClearly() {
        GcpEnvironmentConfig cfg = new GcpEnvironmentConfig();
        cfg.setSecretManagerMode("gcp-secret-manager");
        cfg.setEnvironmentProjects(java.util.Map.of("DEV", project("pulse-489421")));
        GcpSecretManagerService svc = new GcpSecretManagerService(cfg, "/tmp/not-used");

        assertThatThrownBy(() -> svc.buildSecretReference("STAGING", "secret-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STAGING");

        // The DeploymentEnvironment helper itself rejects unknown values
        // before they ever reach a project lookup — proves the boundary
        // exists at the canonical model layer too.
        assertThatThrownBy(() -> DeploymentEnvironment.normalize("preprod"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown deployment environment");
    }

    private GcpSecretManagerService service(String mode, Path tempDir) {
        GcpEnvironmentConfig cfg = new GcpEnvironmentConfig();
        cfg.setSecretManagerMode(mode);
        cfg.setEnvironmentProjects(java.util.Map.of(
                "dev", project("pulse-dev"),
                "prod", project("pulse-prod"),
                "integration", project("pulse-int"),
                "uat", project("pulse-uat"),
                "local", project("pulse-local")
        ));
        String repoBase = tempDir == null ? "/tmp/pulse-test-not-used" : tempDir.toString();
        return new GcpSecretManagerService(cfg, repoBase);
    }

    private SecretNamingContext ctx(String env, String tenant, String resource, String field, String resourceId) {
        return new SecretNamingContext(env, tenant, null, null, resource, field, resourceId);
    }

    private GcpEnvironmentConfig.EnvironmentProject project(String projectId) {
        GcpEnvironmentConfig.EnvironmentProject ep = new GcpEnvironmentConfig.EnvironmentProject();
        ep.setProjectId(projectId);
        ep.setRegion("us-central1");
        return ep;
    }
}
