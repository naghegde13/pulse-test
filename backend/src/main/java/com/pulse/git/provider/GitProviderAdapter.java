package com.pulse.git.provider;

import com.pulse.git.identity.GitHubPatValidationStatus;

import java.time.Instant;
import java.util.List;

/**
 * Phase 6 — narrow Git-provider contract for PR / status / reviewer
 * sync. Implementations:
 *
 * <ul>
 *   <li>{@link GitHubProviderAdapter} — first implementation, PAT classic.</li>
 *   <li>{@code LocalGitProviderAdapter} — fallback for {@code GitRepo.repoType=LOCAL}
 *       which reports PR unsupported with actionable status.</li>
 * </ul>
 *
 * <p>Implementations MUST be deterministic when given a mocked HTTP
 * client; live network calls are confined to the production GitHub
 * client. Tests inject a stubbed {@link GitHubApiClient}.
 */
public interface GitProviderAdapter {

    /** Stable provider id: {@code GITHUB}, {@code LOCAL}, ... */
    String providerId();

    /**
     * Validate a PAT classic value against the provider. {@code repositoryUrl}
     * is optional — when supplied, the adapter additionally checks repo
     * accessibility and returns {@code REPO_ACCESS_DENIED} if the token
     * authenticates but cannot read the repo.
     */
    ValidationResult validateToken(String token, String repositoryUrl);

    /** Open a new pull request. Returns the provider PR number + URL. */
    PullRequestInfo createPullRequest(CreatePullRequest request);

    /** Read live PR status by provider PR number. */
    PullRequestInfo getPullRequest(String repositoryUrl, int prNumber, String tokenReference);

    /** List candidate reviewers for the repo (collaborators / org members the token can see). */
    List<String> listReviewers(String repositoryUrl, String tokenReference);

    /** Sync merge / close state. Used by webhook handler + periodic poller. */
    PullRequestInfo syncState(String repositoryUrl, int prNumber, String tokenReference);

    /** Verify an inbound webhook signature against the configured secret. */
    boolean verifyWebhookSignature(String payload, String signatureHeader, String webhookSecret);

    // ------------------------------------------------------------------
    //  Value records
    // ------------------------------------------------------------------

    /**
     * Result of {@link #validateToken}. Carries the locked status enum
     * + an actionable message + best-effort identity hints (scopes,
     * github username, author name, author email) so the calling
     * service can hydrate the UserGitIdentity row in one shot without
     * collecting those fields from the operator (PKT-FINAL-3 / BUG-03,
     * BUG-04).
     */
    record ValidationResult(
            GitHubPatValidationStatus status,
            String message,
            String scopes,
            String githubUsername,
            String authorName,
            String authorEmail
    ) {
        public static ValidationResult valid(String scopes, String username,
                                             String authorName, String authorEmail) {
            return new ValidationResult(GitHubPatValidationStatus.VALID, null, scopes, username,
                    authorName, authorEmail);
        }

        /**
         * @deprecated supplies the legacy 4-arg shape (scopes + username) with
         *             null author fields. Adapters should populate authorName /
         *             authorEmail via the 6-arg {@link #valid(String, String, String, String)}.
         */
        @Deprecated
        public static ValidationResult valid(String scopes, String username) {
            return new ValidationResult(GitHubPatValidationStatus.VALID, null, scopes, username,
                    null, null);
        }

        public static ValidationResult deny(GitHubPatValidationStatus status, String message) {
            if (status == GitHubPatValidationStatus.VALID) {
                throw new IllegalArgumentException("deny() requires a non-VALID status");
            }
            return new ValidationResult(status, message, null, null, null, null);
        }
    }

    /** Live PR snapshot from the provider. */
    record PullRequestInfo(
            String repositoryUrl,
            int number,
            String title,
            String state,                       // OPEN / MERGED / CLOSED
            String sourceBranch,
            String targetBranch,
            String url,
            String mergeCommitSha,
            Instant mergedAt,
            Instant closedAt,
            List<String> reviewers
    ) {}

    /** Write payload for {@link #createPullRequest}. */
    record CreatePullRequest(
            String repositoryUrl,
            String title,
            String body,
            String sourceBranch,
            String targetBranch,
            String tokenReference,
            List<String> reviewers
    ) {}
}
