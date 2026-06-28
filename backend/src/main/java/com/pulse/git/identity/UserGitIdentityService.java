package com.pulse.git.identity;

import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.service.TenantService;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.config.TenantConfig.TenantDefinition;
import com.pulse.git.provider.GitProviderAdapter;
import com.pulse.git.service.GitIdentityInvalidException;
import com.pulse.git.service.GitIdentityRequiredException;
import com.pulse.secret.service.GcpSecretManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 6 — registration / rotation / revocation of user GitHub PAT
 * classic credentials.
 *
 * <p>Hard contracts (mirroring the plan):
 * <ul>
 *   <li>The actor is ALWAYS resolved server-side from the supplied
 *       {@link CallerContext}; caller-supplied {@code pulseUserId},
 *       {@code credentialReference}, or secret id values are never
 *       honored.</li>
 *   <li>The PAT value is written ONLY to Google Secret Manager via
 *       {@link GcpSecretManagerService}. No row in
 *       {@code user_git_identities} ever stores plaintext.</li>
 *   <li>Read APIs return {@link MaskedGitIdentity} only — never the
 *       PAT value, never the full GSM secret id.</li>
 *   <li>Rotation: write the new GSM secret first, validate it via the
 *       provider adapter, and only mark the row VALID after success;
 *       a failed rotation leaves the prior credential active.</li>
 *   <li>Revocation: disable the GSM secret and flip status to REVOKED.
 *       The row is kept for audit.</li>
 * </ul>
 */
@Service
public class UserGitIdentityService {

    private static final Logger log = LoggerFactory.getLogger(UserGitIdentityService.class);
    static final String PROVIDER_GITHUB = "GITHUB";
    static final String CREDENTIAL_TYPE_PAT_CLASSIC = "PAT_CLASSIC";

    private final UserGitIdentityRepository identityRepo;
    private final GcpSecretManagerService secretManager;
    private final GitProviderAdapter githubProvider;
    private final TenantService tenantService;

    public UserGitIdentityService(UserGitIdentityRepository identityRepo,
                                  GcpSecretManagerService secretManager,
                                  GitProviderAdapter githubProvider,
                                  TenantService tenantService) {
        this.identityRepo = identityRepo;
        this.secretManager = secretManager;
        this.githubProvider = githubProvider;
        this.tenantService = tenantService;
    }

    /**
     * Register a new GitHub PAT for the active actor. The PAT value is
     * passed in directly (the only entry point that ever sees plaintext)
     * and routed straight to GSM. Caller-supplied identity or secret-id
     * fields on the request body are intentionally NOT accepted —
     * everything that identifies the actor comes from {@code caller}.
     *
     * @param caller         server-resolved actor (ignores any spoofed body)
     * @param request        write-only payload: token + masked metadata
     * @return masked status of the registered identity
     */
    @Transactional
    public MaskedGitIdentity register(CallerContext caller, RegisterRequest request) {
        requireActor(caller);
        if (request == null || request.token() == null || request.token().isBlank()) {
            throw new IllegalArgumentException("token is required");
        }
        warnIfLegacyFieldsPresent(request, "register");
        String tenantId = caller.tenantId();
        String pulseUserId = caller.userId();
        // Build the GSM secret id deterministically. <stableId> is
        // generated server-side so rotation can mint a new version
        // without changing the logical identity.
        String stableId = UUID.randomUUID().toString().substring(0, 12);
        String tenantSlug = resolveTenantSlug(tenantId);
        String secretId = secretIdFor(tenantSlug, pulseUserId, stableId);
        Map<String, String> labels = labelsFor(tenantSlug, pulseUserId);
        String reference = secretManager.createOrUpdateSecret(
                "dev", secretId, request.token(), labels);

        UserGitIdentity row = identityRepo
                .findByTenantIdAndPulseUserIdAndProvider(tenantId, pulseUserId, PROVIDER_GITHUB)
                .orElseGet(UserGitIdentity::new);
        row.setTenantId(tenantId);
        row.setPulseUserId(pulseUserId);
        row.setProvider(PROVIDER_GITHUB);
        row.setCredentialType(CREDENTIAL_TYPE_PAT_CLASSIC);
        row.setCredentialReference(reference);
        // PKT-FINAL-3 (BUG-03/04): identity fields (githubUsername,
        // authorName, authorEmail, scopes) are now auto-populated from the
        // validation response below — never from request body. The legacy
        // accessors on RegisterRequest remain so existing callers don't 400,
        // but the values are ignored.
        row.setStatus(GitHubPatValidationStatus.PENDING_VALIDATION.name());
        row.setLastValidationError(null);
        row.setRevokedAt(null);
        row = identityRepo.save(row);

        // Validate the freshly-stored token against the provider so the
        // row lands in VALID (or a deny status). This is the moment the
        // identity becomes usable for git operations.
        GitProviderAdapter.ValidationResult validation = githubProvider.validateToken(
                request.token(), null);
        applyValidationOutcome(row, validation, Instant.now());
        return toMaskedView(identityRepo.save(row));
    }

    /**
     * Rotate the active actor's PAT. Verify-before-disable: write the
     * new secret + validate it; if validation fails, leave the prior
     * credential active and surface the failure status.
     */
    @Transactional
    public MaskedGitIdentity rotate(CallerContext caller, RotateRequest request) {
        requireActor(caller);
        if (request == null || request.token() == null || request.token().isBlank()) {
            throw new IllegalArgumentException("token is required");
        }
        warnIfLegacyRotateFieldsPresent(request);
        UserGitIdentity row = identityRepo
                .findByTenantIdAndPulseUserIdAndProvider(
                        caller.tenantId(), caller.userId(), PROVIDER_GITHUB)
                .orElseThrow(() -> new ResourceNotFoundException("UserGitIdentity",
                        caller.userId()));
        // Stage the new secret. We mint a new stableId so the new
        // version doesn't overwrite the prior one until validation
        // succeeds.
        String tenantSlug = resolveTenantSlug(caller.tenantId());
        String stableId = UUID.randomUUID().toString().substring(0, 12);
        String secretId = secretIdFor(tenantSlug, caller.userId(), stableId);
        Map<String, String> labels = labelsFor(tenantSlug, caller.userId());
        String newReference = secretManager.createOrUpdateSecret(
                "dev", secretId, request.token(), labels);

        GitProviderAdapter.ValidationResult validation = githubProvider.validateToken(
                request.token(), null);
        if (validation.status() != GitHubPatValidationStatus.VALID) {
            // Phase 6 closeout — verify-before-disable means a FAILED
            // rotation must NOT invalidate the active credential. We:
            //   * keep the existing credentialReference unchanged;
            //   * keep the existing row.status (VALID stays VALID);
            //   * do NOT disable the prior GSM secret;
            //   * record the failed-rotation diagnostic on metadata so
            //     the masked response surfaces it without making
            //     downstream readers think the active credential is
            //     broken.
            // The new secret was already written to GSM (verify-before-
            // disable order); it stays orphaned in the project so a
            // platform op can audit it. Pulse never points at it.
            log.warn("Rotation validation failed for user {}: {} ({})",
                    caller.userId(), validation.status(), validation.message());
            Map<String, Object> meta = row.getMetadata() == null
                    ? new java.util.LinkedHashMap<>()
                    : new java.util.LinkedHashMap<>(row.getMetadata());
            meta.put("lastRotationAttemptStatus", validation.status().name());
            meta.put("lastRotationAttemptError", validation.message());
            meta.put("lastRotationAttemptAt", Instant.now().toString());
            row.setMetadata(meta);
            return toMaskedView(identityRepo.save(row));
        }
        // New token is valid — disable the prior secret and switch
        // credentialReference. Phase 6 closeout: route the disable
        // through the FULL prior gcp-sm:// URI so the right project +
        // pinned version is acted on, instead of dropping back to the
        // hardcoded "dev" environment. Disabling is best-effort; if it
        // fails we still flip the pointer so the next git operation uses
        // the new token.
        String priorReference = row.getCredentialReference();
        try {
            if (priorReference != null && priorReference.startsWith("gcp-sm://")) {
                secretManager.disableSecretByReference(priorReference);
            }
        } catch (RuntimeException disableErr) {
            log.warn("Failed to disable prior secret for user {}: {}",
                    caller.userId(), disableErr.getMessage());
        }
        row.setCredentialReference(newReference);
        row.setLastRotatedAt(Instant.now());
        row.setRevokedAt(null);
        // Successful rotation clears any stale failed-rotation diagnostic.
        if (row.getMetadata() != null) {
            Map<String, Object> meta = new java.util.LinkedHashMap<>(row.getMetadata());
            meta.remove("lastRotationAttemptStatus");
            meta.remove("lastRotationAttemptError");
            meta.remove("lastRotationAttemptAt");
            row.setMetadata(meta);
        }
        applyValidationOutcome(row, validation, Instant.now());
        return toMaskedView(identityRepo.save(row));
    }

    /** Revoke the active actor's PAT. Disables the GSM secret + flips status to REVOKED. */
    @Transactional
    public MaskedGitIdentity revoke(CallerContext caller) {
        requireActor(caller);
        UserGitIdentity row = identityRepo
                .findByTenantIdAndPulseUserIdAndProvider(
                        caller.tenantId(), caller.userId(), PROVIDER_GITHUB)
                .orElseThrow(() -> new ResourceNotFoundException("UserGitIdentity",
                        caller.userId()));
        // Phase 6 closeout: route revoke disable through the full
        // gcp-sm:// URI so the secret is disabled in the project +
        // version it actually lives in, not in a hardcoded "dev"
        // environment.
        String reference = row.getCredentialReference();
        try {
            if (reference != null && reference.startsWith("gcp-sm://")) {
                secretManager.disableSecretByReference(reference);
            }
        } catch (RuntimeException disableErr) {
            log.warn("Failed to disable GSM secret on revoke for user {}: {}",
                    caller.userId(), disableErr.getMessage());
        }
        row.setStatus(GitHubPatValidationStatus.REVOKED.name());
        row.setRevokedAt(Instant.now());
        return toMaskedView(identityRepo.save(row));
    }

    /** Read the active actor's masked identity (no token, no full secret id). */
    @Transactional(readOnly = true)
    public Optional<MaskedGitIdentity> getMasked(CallerContext caller) {
        requireActor(caller);
        return identityRepo
                .findByTenantIdAndPulseUserIdAndProvider(
                        caller.tenantId(), caller.userId(), PROVIDER_GITHUB)
                .map(this::toMaskedView);
    }

    @Transactional(readOnly = true)
    public UserGitIdentity requireValidIdentity(CallerContext caller) {
        requireActor(caller);
        UserGitIdentity row = identityRepo
                .findByTenantIdAndPulseUserIdAndProvider(
                        caller.tenantId(), caller.userId(), PROVIDER_GITHUB)
                .orElseThrow(() -> new GitIdentityRequiredException(
                        "Register a valid GitHub PAT identity before using remote tenant git operations."));
        if (!PROVIDER_GITHUB.equals(row.getProvider())) {
            throw new GitIdentityInvalidException("Only GitHub PAT identities are supported for remote git operations.");
        }
        if (!GitHubPatValidationStatus.VALID.name().equals(row.getStatus())) {
            throw new GitIdentityInvalidException("GitHub identity is not valid: " + row.getStatus());
        }
        if (isBlank(row.getCredentialReference())
                || isBlank(row.getAuthorName())
                || isBlank(row.getAuthorEmail())) {
            throw new GitIdentityInvalidException(
                    "GitHub identity must include credential reference, author name, and author email.");
        }
        return row;
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /**
     * PKT-FINAL-3 (BUG-03): log once per call when a caller still submits
     * fields the identity panel used to require. The values are intentionally
     * dropped on the floor; the legacy fields will be deleted from the DTO
     * one release after this packet ships.
     */
    private static void warnIfLegacyFieldsPresent(RegisterRequest request, String op) {
        if (request == null) return;
        java.util.List<String> ignored = new java.util.ArrayList<>(4);
        if (isNotBlank(request.githubUsername())) ignored.add("githubUsername");
        if (isNotBlank(request.authorName())) ignored.add("authorName");
        if (isNotBlank(request.authorEmail())) ignored.add("authorEmail");
        if (isNotBlank(request.scopes())) ignored.add("scopes");
        if (isNotBlank(request.repositoryUrl())) ignored.add("repositoryUrl");
        if (!ignored.isEmpty()) {
            log.warn("git-identity.{} received deprecated fields {} — values ignored; "
                    + "identity is now auto-populated from the PAT owner. "
                    + "Remove these fields from your client; they will be rejected in a future release.",
                    op, ignored);
        }
    }

    private static void warnIfLegacyRotateFieldsPresent(RotateRequest request) {
        if (request != null && isNotBlank(request.repositoryUrl())) {
            log.warn("git-identity.rotate received deprecated field repositoryUrl — "
                    + "value ignored. Remove from your client; will be rejected in a future release.");
        }
    }

    /**
     * Phase 6 contract: fail fast when no actor was resolved.
     *
     * <p>SU-6 / BUG-59: split into two distinct error messages so
     * operators can tell at a glance whether the request is missing the
     * authenticated user (login / JWT issue) or the tenant header
     * (X-Tenant-ID propagation issue). Previously a single message
     * conflated the two and sent every triager to debug auth even when
     * the real problem was a missing tenant header.
     */
    private static void requireActor(CallerContext caller) {
        if (caller == null
                || caller.userId() == null || caller.userId().isBlank()) {
            throw new IllegalArgumentException(
                    "Authenticated user could not be resolved server-side; "
                            + "user PAT operations cannot accept request-body identity. "
                            + "Verify auth headers / JWT.");
        }
        if (caller.tenantId() == null || caller.tenantId().isBlank()) {
            throw new IllegalArgumentException(
                    "Tenant context could not be resolved server-side; "
                            + "user PAT operations require an X-Tenant-ID header on the request.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void applyValidationOutcome(UserGitIdentity row,
                                        GitProviderAdapter.ValidationResult validation,
                                        Instant now) {
        row.setStatus(validation.status().name());
        if (validation.status() == GitHubPatValidationStatus.VALID) {
            row.setVerifiedAt(now);
            row.setLastValidationError(null);
        } else {
            row.setLastValidationError(validation.message());
        }
        if (validation.scopes() != null) {
            row.setScopes(validation.scopes());
        }
        // PKT-FINAL-3 (BUG-03 / BUG-04): the PAT *is* the identity. Always
        // overwrite the persisted identity fields from the validation
        // response when present, so operator-typed values (legacy DTO fields)
        // can never drift from the actual PAT owner. Only fall back to a
        // user-supplied value if the validation result didn't include one.
        if (isNotBlank(validation.githubUsername())) {
            row.setGithubUsername(validation.githubUsername());
        }
        if (isNotBlank(validation.authorName())) {
            row.setAuthorName(validation.authorName());
        }
        if (isNotBlank(validation.authorEmail())) {
            row.setAuthorEmail(validation.authorEmail());
        }
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * GSM secret ID per Phase 6 plan:
     * {@code pulse-<tenant>-user-<pulseUserId>-github-pat-<stableId>}.
     * The user id is normalized to GSM-safe characters.
     */
    static String secretIdFor(String tenantSlug, String pulseUserId, String stableId) {
        String safeUser = pulseUserId == null
                ? "unknown"
                : pulseUserId.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-+|-+$", "");
        return "pulse-" + tenantSlug + "-user-" + safeUser + "-github-pat-" + stableId;
    }

    private Map<String, String> labelsFor(String tenantSlug, String pulseUserId) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app", "pulse");
        labels.put("kind", "git-credential");
        labels.put("provider", "github");
        labels.put("tenant", tenantSlug);
        labels.put("pulse_user_id", pulseUserId.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-"));
        labels.put("credential_type", "pat_classic");
        return labels;
    }

    private String resolveTenantSlug(String tenantId) {
        try {
            TenantDefinition def = tenantService.getTenant(tenantId);
            return def.getSlug() == null ? tenantId : def.getSlug();
        } catch (RuntimeException e) {
            // Tenant service unavailable in some test contexts — fall
            // back to a normalized id.
            return tenantId.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9-]+", "-");
        }
    }

    private MaskedGitIdentity toMaskedView(UserGitIdentity row) {
        Map<String, Object> meta = row.getMetadata() == null ? Map.of() : row.getMetadata();
        return new MaskedGitIdentity(
                row.getId(),
                row.getTenantId(),
                row.getPulseUserId(),
                row.getProvider(),
                row.getCredentialType(),
                MaskedGitIdentity.maskReference(row.getCredentialReference()),
                row.getGithubUsername(),
                row.getAuthorName(),
                row.getAuthorEmail(),
                row.getScopes(),
                row.getStatus(),
                row.getVerifiedAt(),
                row.getExpiresAt(),
                row.getLastRotatedAt(),
                row.getRevokedAt(),
                row.getLastValidationError(),
                stringOrNull(meta.get("lastRotationAttemptStatus")),
                stringOrNull(meta.get("lastRotationAttemptError")),
                stringOrNull(meta.get("lastRotationAttemptAt")));
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * Write payload for {@code POST /api/v1/users/me/git-identity}.
     * Deliberately has NO {@code pulseUserId} / {@code credentialReference}
     * / {@code secretId} fields — every actor field is server-resolved.
     */
    public record RegisterRequest(
            String token,
            String githubUsername,
            String authorName,
            String authorEmail,
            String scopes,
            String repositoryUrl
    ) {}

    /** Write payload for {@code POST /api/v1/users/me/git-identity/rotate}. */
    public record RotateRequest(String token, String repositoryUrl) {}
}
