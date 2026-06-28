package com.pulse.secret.service;

import com.pulse.auth.service.TenantGcpCredentialService;
import com.pulse.auth.service.TenantService;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.config.GcpEnvironmentConfig;
import com.pulse.config.TenantConfig.TenantDefinition;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.CredentialStatus;
import com.pulse.sor.model.Domain;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PKT-0016: Credential Validation Service tests.
 * Covers: UNTESTED→VALID, INVALID, BLOCKED, FAILED transitions,
 * readiness consumption, redaction proof, and negative evidence.
 */
@ExtendWith(MockitoExtension.class)
class CredentialValidationServiceTest {

    @Mock private CredentialProfileRepository credRepo;
    @Mock private ConnectorInstanceRepository ciRepo;
    @Mock private SystemOfRecordRepository sorRepo;
    @Mock private GcpSecretManagerService gcpSecretManagerService;
    @Mock private GcpEnvironmentConfig gcpEnvironmentConfig;
    @Mock private TenantGcpCredentialService tenantCredentialService;

    // Real collaborators for contract verification
    private final SecretReferenceService secretReferenceService = new SecretReferenceService();

    private CredentialValidationService validationService;
    private CredentialPersistenceService persistenceService;
    private SecretAuthorityReadinessService authorityService;

    @BeforeEach
    void setUp() {
        // Persistence service uses real SecretReferenceService
        TenantService tenantService = mock(TenantService.class);
        DomainRepository domainRepo = mock(DomainRepository.class);
        persistenceService = new CredentialPersistenceService(
                credRepo, ciRepo, sorRepo, domainRepo, tenantService,
                gcpSecretManagerService, gcpEnvironmentConfig, secretReferenceService);

        authorityService = new SecretAuthorityReadinessService(gcpEnvironmentConfig, tenantCredentialService, null);

        validationService = new CredentialValidationService(
                credRepo, ciRepo, sorRepo, gcpSecretManagerService,
                authorityService, persistenceService);
    }

    // -----------------------------------------------------------------------
    //  Happy path: UNTESTED → VALID
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("UNTESTED → VALID transition")
    class UntestedToValid {

        @Test
        @DisplayName("Credential with resolvable secret refs transitions to VALID")
        void validSecretRefsProduceValid() {
            stubConnectorAndSor("ci-1", "sor-1", "tenant-1");
            stubCredentialWithSecretRefs("ci-1", "dev", CredentialStatus.UNTESTED,
                    Map.of("host", "db.example.com"),
                    Map.of("password", "gcp-sm://projects/pulse-dev/secrets/db-pass/versions/latest"));
            stubLocalStubMode();
            when(gcpSecretManagerService.secretExists("dev", "db-pass")).thenReturn(true);
            when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            CredentialProfile result = validationService.validate("ci-1", "dev");

            assertThat(result.getStatus()).isEqualTo(CredentialStatus.VALID);
            assertThat(result.getValidationCategory()).isEqualTo("LOCAL_STUB_VALID");
            assertThat(result.getValidationReason()).isNull();
            assertThat(result.getLastValidatedAt()).isNotNull();
            assertThat(result.getLastTestedAt()).isNotNull();
        }

        @Test
        @DisplayName("Metadata-only credential (no secret refs) transitions to VALID")
        void metadataOnlyCredentialProducesValid() {
            stubConnectorAndSor("ci-1", "sor-1", "tenant-1");
            stubCredentialWithSecretRefs("ci-1", "dev", CredentialStatus.UNTESTED,
                    Map.of("host", "db.example.com", "port", "5432"),
                    Map.of());
            stubLocalStubMode();
            when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            CredentialProfile result = validationService.validate("ci-1", "dev");

            assertThat(result.getStatus()).isEqualTo(CredentialStatus.VALID);
            assertThat(result.getValidationCategory()).isEqualTo("METADATA_ONLY");
        }
    }

    // -----------------------------------------------------------------------
    //  INVALID outcomes
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("INVALID outcomes")
    class InvalidOutcomes {

        @Test
        @DisplayName("Missing secret reference produces INVALID")
        void missingSecretRefProducesInvalid() {
            stubConnectorAndSor("ci-1", "sor-1", "tenant-1");
            stubCredentialWithSecretRefs("ci-1", "dev", CredentialStatus.UNTESTED,
                    Map.of("host", "db.example.com"),
                    Map.of("password", "gcp-sm://projects/pulse-dev/secrets/db-pass/versions/latest"));
            stubLocalStubMode();
            when(gcpSecretManagerService.secretExists("dev", "db-pass")).thenReturn(false);
            when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            CredentialProfile result = validationService.validate("ci-1", "dev");

            assertThat(result.getStatus()).isEqualTo(CredentialStatus.INVALID);
            assertThat(result.getValidationCategory()).isEqualTo("SECRET_NOT_FOUND");
            assertThat(result.getValidationReason()).contains("password");
        }

        @Test
        @DisplayName("SKIPPED credential produces INVALID with reason")
        void skippedCredentialProducesInvalid() {
            stubConnectorAndSor("ci-1", "sor-1", "tenant-1");
            stubCredentialWithSecretRefs("ci-1", "dev", CredentialStatus.SKIPPED,
                    Map.of(), Map.of());
            when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            CredentialProfile result = validationService.validate("ci-1", "dev");

            assertThat(result.getStatus()).isEqualTo(CredentialStatus.INVALID);
            assertThat(result.getValidationCategory()).isEqualTo("SKIPPED_CREDENTIAL");
            assertThat(result.getValidationReason()).contains("skipped");
        }

        @Test
        @DisplayName("Empty credential (no metadata, no refs) produces INVALID")
        void emptyCredentialProducesInvalid() {
            stubConnectorAndSor("ci-1", "sor-1", "tenant-1");
            stubCredentialWithSecretRefs("ci-1", "dev", CredentialStatus.UNTESTED,
                    Map.of(), Map.of());
            stubLocalStubMode();
            when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            CredentialProfile result = validationService.validate("ci-1", "dev");

            assertThat(result.getStatus()).isEqualTo(CredentialStatus.INVALID);
            assertThat(result.getValidationCategory()).isEqualTo("EMPTY_CREDENTIAL");
        }
    }

    // -----------------------------------------------------------------------
    //  BLOCKED outcomes
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("BLOCKED outcomes")
    class BlockedOutcomes {

        @Test
        @DisplayName("BLOCKED secret authority mode produces BLOCKED status")
        void blockedAuthorityProducesBlocked() {
            stubConnectorAndSor("ci-1", "sor-1", "tenant-1");
            stubCredentialWithSecretRefs("ci-1", "dev", CredentialStatus.UNTESTED,
                    Map.of("host", "db.example.com"),
                    Map.of("password", "gcp-sm://projects/pulse-dev/secrets/db-pass/versions/latest"));
            // Null secret manager mode → BLOCKED
            when(gcpEnvironmentConfig.getSecretManagerMode()).thenReturn(null);
            when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            CredentialProfile result = validationService.validate("ci-1", "dev");

            assertThat(result.getStatus()).isEqualTo(CredentialStatus.BLOCKED);
            assertThat(result.getValidationCategory()).isEqualTo("SECRET_AUTHORITY_BLOCKED");
            assertThat(result.getValidationReason()).contains("BLOCKED");
        }
    }

    // -----------------------------------------------------------------------
    //  FAILED outcomes
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("FAILED outcomes")
    class FailedOutcomes {

        @Test
        @DisplayName("Secret Manager access error produces FAILED")
        void secretManagerErrorProducesFailed() {
            stubConnectorAndSor("ci-1", "sor-1", "tenant-1");
            stubCredentialWithSecretRefs("ci-1", "dev", CredentialStatus.UNTESTED,
                    Map.of("host", "db.example.com"),
                    Map.of("password", "gcp-sm://projects/pulse-dev/secrets/db-pass/versions/latest"));
            stubLocalStubMode();
            when(gcpSecretManagerService.secretExists("dev", "db-pass"))
                    .thenThrow(new SecretManagerException("Connection refused", null));
            when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            CredentialProfile result = validationService.validate("ci-1", "dev");

            assertThat(result.getStatus()).isEqualTo(CredentialStatus.FAILED);
            assertThat(result.getValidationCategory()).isEqualTo("SECRET_ACCESS_ERROR");
            assertThat(result.getValidationReason()).contains("password");
        }
    }

    // -----------------------------------------------------------------------
    //  Re-validation: already-VALID credential can re-validate
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Re-validation")
    class Revalidation {

        @Test
        @DisplayName("Already VALID credential re-validates successfully")
        void revalidateValidCredential() {
            stubConnectorAndSor("ci-1", "sor-1", "tenant-1");
            stubCredentialWithSecretRefs("ci-1", "dev", CredentialStatus.VALID,
                    Map.of("host", "db.example.com"),
                    Map.of("password", "gcp-sm://projects/pulse-dev/secrets/db-pass/versions/latest"));
            stubLocalStubMode();
            when(gcpSecretManagerService.secretExists("dev", "db-pass")).thenReturn(true);
            when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            CredentialProfile result = validationService.validate("ci-1", "dev");

            assertThat(result.getStatus()).isEqualTo(CredentialStatus.VALID);
        }

        @Test
        @DisplayName("INVALID credential can re-validate to VALID after fix")
        void invalidCanRevalidateToValid() {
            stubConnectorAndSor("ci-1", "sor-1", "tenant-1");
            stubCredentialWithSecretRefs("ci-1", "dev", CredentialStatus.INVALID,
                    Map.of("host", "db.example.com"),
                    Map.of("password", "gcp-sm://projects/pulse-dev/secrets/db-pass/versions/latest"));
            stubLocalStubMode();
            when(gcpSecretManagerService.secretExists("dev", "db-pass")).thenReturn(true);
            when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            CredentialProfile result = validationService.validate("ci-1", "dev");

            assertThat(result.getStatus()).isEqualTo(CredentialStatus.VALID);
        }
    }

    // -----------------------------------------------------------------------
    //  Redaction proof: no secret values leak
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Redaction proof")
    class RedactionProof {

        @Test
        @DisplayName("Validated result never contains secret values")
        void validatedResultNeverContainsSecretValues() {
            stubConnectorAndSor("ci-1", "sor-1", "tenant-1");
            stubCredentialWithSecretRefs("ci-1", "dev", CredentialStatus.UNTESTED,
                    Map.of("host", "db.example.com"),
                    Map.of("password", "gcp-sm://projects/pulse-dev/secrets/db-pass/versions/latest"));
            stubLocalStubMode();
            when(gcpSecretManagerService.secretExists("dev", "db-pass")).thenReturn(true);
            when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            CredentialProfile result = validationService.validate("ci-1", "dev");

            // The password field must not appear in metadataConfig (non-secret view)
            assertThat(result.getMetadataConfig()).doesNotContainKey("password");
            // Secret refs show reference URI, never the actual secret value
            assertThat(result.getSecretRefs().get("password"))
                    .startsWith("gcp-sm://");
            // The secret metadata marks the password field as a secret reference
            assertThat(result.getSecretMetadata().get("password").secretReference()).isTrue();
            // No plaintext secret value appears anywhere in the serialized fields
            assertThat(result.getMetadataConfig().values().toString()).doesNotContain("actual-secret");
        }

        @Test
        @DisplayName("Validation category and reason never contain secret material")
        void validationMetadataNeverContainsSecrets() {
            stubConnectorAndSor("ci-1", "sor-1", "tenant-1");
            stubCredentialWithSecretRefs("ci-1", "dev", CredentialStatus.UNTESTED,
                    Map.of("host", "db.example.com"),
                    Map.of("password", "gcp-sm://projects/pulse-dev/secrets/db-pass/versions/latest"));
            stubLocalStubMode();
            when(gcpSecretManagerService.secretExists("dev", "db-pass")).thenReturn(false);
            when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            CredentialProfile result = validationService.validate("ci-1", "dev");

            // Reason may reference field name but never actual secret value
            assertThat(result.getValidationReason()).doesNotContain("gcp-sm://");
            assertThat(result.getValidationCategory()).doesNotContain("gcp-sm://");
        }
    }

    // -----------------------------------------------------------------------
    //  Not-found errors
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Not-found errors")
    class NotFoundErrors {

        @Test
        @DisplayName("Missing connector instance throws ResourceNotFoundException")
        void missingConnectorInstanceThrows() {
            when(ciRepo.findById("ci-missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> validationService.validate("ci-missing", "dev"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Missing credential profile throws ResourceNotFoundException")
        void missingCredentialProfileThrows() {
            stubConnectorAndSor("ci-1", "sor-1", "tenant-1");
            when(credRepo.findByConnectorInstanceIdAndEnvironment("ci-1", "dev"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> validationService.validate("ci-1", "dev"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // -----------------------------------------------------------------------
    //  Environment normalization
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Environment normalization")
    class EnvironmentNormalization {

        @Test
        @DisplayName("Legacy uppercase env is normalized before lookup")
        void legacyUppercaseEnvNormalized() {
            stubConnectorAndSor("ci-1", "sor-1", "tenant-1");
            stubCredentialWithSecretRefs("ci-1", "prod", CredentialStatus.UNTESTED,
                    Map.of("host", "prod-db.example.com"),
                    Map.of());
            stubLocalStubMode();
            when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            // Pass legacy uppercase — should normalize to "prod" and find the credential
            CredentialProfile result = validationService.validate("ci-1", "PRODUCTION");

            // Credential has metadata (host), so it validates as METADATA_ONLY → VALID
            assertThat(result.getStatus()).isEqualTo(CredentialStatus.VALID);
            assertThat(result.getValidationCategory()).isEqualTo("METADATA_ONLY");
        }
    }

    // -----------------------------------------------------------------------
    //  Persistence: saved entity has correct status and timestamps
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Persistence verification")
    class PersistenceVerification {

        @Test
        @DisplayName("Saved entity captures validation metadata")
        void savedEntityCapturesValidationMetadata() {
            stubConnectorAndSor("ci-1", "sor-1", "tenant-1");
            stubCredentialWithSecretRefs("ci-1", "dev", CredentialStatus.UNTESTED,
                    Map.of("host", "db.example.com"),
                    Map.of("password", "gcp-sm://projects/pulse-dev/secrets/db-pass/versions/latest"));
            stubLocalStubMode();
            when(gcpSecretManagerService.secretExists("dev", "db-pass")).thenReturn(true);
            ArgumentCaptor<CredentialProfile> captor = ArgumentCaptor.forClass(CredentialProfile.class);
            when(credRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            validationService.validate("ci-1", "dev");

            CredentialProfile saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(CredentialStatus.VALID);
            assertThat(saved.getLastTestedAt()).isNotNull();
            assertThat(saved.getLastValidatedAt()).isNotNull();
            assertThat(saved.getValidationCategory()).isEqualTo("LOCAL_STUB_VALID");
            assertThat(saved.getValidationReason()).isNull();
        }
    }

    // -----------------------------------------------------------------------
    //  Vault reference: structurally accepted
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Vault references")
    class VaultReferences {

        @Test
        @DisplayName("Vault references are structurally accepted as valid")
        void vaultRefsAccepted() {
            stubConnectorAndSor("ci-1", "sor-1", "tenant-1");
            stubCredentialWithSecretRefs("ci-1", "dev", CredentialStatus.UNTESTED,
                    Map.of("host", "db.example.com"),
                    Map.of("password", "vault://secret/data/db-password"));
            stubLocalStubMode();
            when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            CredentialProfile result = validationService.validate("ci-1", "dev");

            assertThat(result.getStatus()).isEqualTo(CredentialStatus.VALID);
            assertThat(result.getValidationCategory()).isEqualTo("LOCAL_STUB_VALID");
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private void stubConnectorAndSor(String ciId, String sorId, String tenantId) {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setId(ciId);
        ci.setSorId(sorId);
        ci.setName("Test Connector");
        when(ciRepo.findById(ciId)).thenReturn(Optional.of(ci));

        SystemOfRecord sor = new SystemOfRecord();
        sor.setId(sorId);
        sor.setTenantId(tenantId);
        sor.setMetadata(Map.of());
        when(sorRepo.findById(sorId)).thenReturn(Optional.of(sor));
    }

    private void stubCredentialWithSecretRefs(
            String ciId, String env, CredentialStatus status,
            Map<String, Object> metadata, Map<String, String> secretRefs) {
        CredentialProfile cred = new CredentialProfile();
        cred.setId("cred-" + ciId + "-" + env);
        cred.setConnectorInstanceId(ciId);
        cred.setEnvironment(env);
        cred.setStatus(status);
        cred.setConnectionMetadata(metadata);
        cred.setSecretReferences(secretRefs);
        when(credRepo.findByConnectorInstanceIdAndEnvironment(ciId, env))
                .thenReturn(Optional.of(cred));
    }

    private void stubLocalStubMode() {
        when(gcpEnvironmentConfig.getSecretManagerMode()).thenReturn("local-stub");
    }
}
