package com.pulse.secret.service;

import com.pulse.config.GcpEnvironmentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GcpSecretManagerServiceTest {

    @Test
    void buildSecretId_localStub_keepsEnvInName(@TempDir Path tempDir) {
        GcpSecretManagerService service = service("local-stub", tempDir);

        SecretNamingContext context = new SecretNamingContext(
                "DEV", "home-lending", "sales", "source", "los-oracle", "password", "ci-1");

        assertThat(service.buildSecretId(context))
                .isEqualTo("pulse-dev-home-lending-sales-source-los-oracle-password-ci-1");
    }

    @Test
    void buildSecretId_gsm_dropsEnvFromName() {
        GcpSecretManagerService service = service("gcp-secret-manager", null);

        SecretNamingContext context = new SecretNamingContext(
                "DEV", "home-lending", "sales", "source", "los-oracle", "password", "ci-1");

        assertThat(service.buildSecretId(context))
                .isEqualTo("pulse-home-lending-sales-source-los-oracle-password-ci-1");
    }

    @Test
    void buildSecretId_gsm_capsLengthAt255AndStillExcludesEnv() {
        GcpSecretManagerService service = service("gcp-secret-manager", null);

        String longResource = "a".repeat(300);
        SecretNamingContext context = new SecretNamingContext(
                "DEV", "home-lending", "sales", "source", longResource, "password", "ci-1");

        String id = service.buildSecretId(context);
        assertThat(id).hasSizeLessThanOrEqualTo(255);
        assertThat(id).startsWith("pulse-home-lending-sales-source-");
        assertThat(id).doesNotStartWith("pulse-dev-");
    }

    @Test
    void buildSecretId_gsm_caseInsensitiveModeMatch() {
        GcpSecretManagerService service = service("GCP-SECRET-MANAGER", null);

        SecretNamingContext context = new SecretNamingContext(
                "DEV", "tenant-a", null, null, "res", "field", "id-1");

        // Anything not equal-ignore-case to "local-stub" is treated as real GSM → no env in id.
        assertThat(service.buildSecretId(context))
                .isEqualTo("pulse-tenant-a-default-source-res-field-id-1");
    }

    private GcpSecretManagerService service(String mode, Path localStubRoot) {
        GcpEnvironmentConfig cfg = new GcpEnvironmentConfig();
        cfg.setSecretManagerMode(mode);
        String repoBase = localStubRoot == null ? "/tmp/pulse-test-not-used" : localStubRoot.toString();
        return new GcpSecretManagerService(cfg, repoBase);
    }

    // PKT-FINAL-4 (BUG-38): the prior `initLocalStubRoot` fallback tests moved
    // to {@link SecretStubBaseServiceTest}. The fallback logic now lives in
    // {@link SecretStubBaseService}, so this class only exercises the naming
    // / read / write behavior of `GcpSecretManagerService` itself.
}
