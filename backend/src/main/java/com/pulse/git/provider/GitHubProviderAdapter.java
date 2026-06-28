package com.pulse.git.provider;

import com.pulse.git.identity.GitHubPatValidationStatus;
import com.pulse.secret.service.GcpSecretManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 6 — GitHub PAT-classic-backed implementation of
 * {@link GitProviderAdapter}.
 *
 * <p>HTTP I/O is delegated to {@link GitHubApiClient} so tests inject a
 * deterministic stub. Status mapping follows the locked
 * {@link GitHubPatValidationStatus} table:
 * <ul>
 *   <li>HTTP 401 → {@code INVALID_TOKEN}</li>
 *   <li>HTTP 403 with rate-limit / scope hints → {@code INSUFFICIENT_SCOPE}
 *       or {@code REPO_ACCESS_DENIED}</li>
 *   <li>HTTP 404 on the repo probe → {@code REPO_ACCESS_DENIED}</li>
 *   <li>HTTP 5xx / network → {@code PROVIDER_UNAVAILABLE}</li>
 * </ul>
 */
@Service
public class GitHubProviderAdapter implements GitProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(GitHubProviderAdapter.class);
    private static final String API_USER = "/user";
    private static final String API_USER_EMAILS = "/user/emails";

    private final GitHubApiClient client;
    private final GcpSecretManagerService secretManager;
    /**
     * PKT-FINAL-3 (BUG-08): track whether the real GitHub HTTP client is
     * active so 5xx responses from the stub get attributed to "PULSE in stub
     * mode" rather than falsely blaming GitHub.
     */
    private final boolean githubEnabled;

    @Autowired
    public GitHubProviderAdapter(GitHubApiClient client,
                                 GcpSecretManagerService secretManager,
                                 @Value("${pulse.git.github.enabled:false}") boolean githubEnabled) {
        this.client = client;
        this.secretManager = secretManager;
        this.githubEnabled = githubEnabled;
    }

    /** Test-friendly overload preserving the prior constructor shape. */
    public GitHubProviderAdapter(GitHubApiClient client,
                                 GcpSecretManagerService secretManager) {
        this(client, secretManager, false);
    }

    /** Visible for tests / readiness service. */
    public boolean isGithubClientEnabled() {
        return githubEnabled;
    }

    @Override
    public String providerId() {
        return "GITHUB";
    }

    @Override
    public ValidationResult validateToken(String token, String repositoryUrl) {
        if (token == null || token.isBlank()) {
            return ValidationResult.deny(GitHubPatValidationStatus.INVALID_TOKEN,
                    "PAT value is empty.");
        }
        // Phase 6: probe /user first. Map HTTP status to the locked
        // validation enum. Then if a repository is configured, probe
        // /repos/{owner}/{repo} too so REPO_ACCESS_DENIED is detected.
        GitHubApiClient.Response userResp;
        try {
            userResp = client.get(API_USER, token);
        } catch (RuntimeException providerErr) {
            log.warn("GitHub /user probe failed: {}", providerErr.getMessage());
            return ValidationResult.deny(GitHubPatValidationStatus.PROVIDER_UNAVAILABLE,
                    "GitHub API unreachable: " + providerErr.getMessage());
        }
        if (userResp.statusCode() == 401) {
            return ValidationResult.deny(GitHubPatValidationStatus.INVALID_TOKEN,
                    "GitHub rejected the token (401).");
        }
        if (userResp.statusCode() == 403) {
            // 403 on /user usually means the token authenticates but
            // lacks required scopes (or is rate-limited). Tease apart
            // when possible.
            return ValidationResult.deny(GitHubPatValidationStatus.INSUFFICIENT_SCOPE,
                    "GitHub returned 403 on /user (insufficient scopes or rate limited).");
        }
        if (userResp.statusCode() >= 500) {
            // PKT-FINAL-3 (BUG-08): the stub client returns 503 without ever
            // contacting GitHub. Distinguish "PULSE not configured" from "real
            // upstream failure" so operators stop trying to debug a fake outage.
            if (!githubEnabled) {
                return ValidationResult.deny(GitHubPatValidationStatus.PROVIDER_UNAVAILABLE,
                        "PULSE GitHub client is in stub mode (pulse.git.github.enabled=false). "
                                + "No HTTP call was made to api.github.com. "
                                + "Set PULSE_GIT_GITHUB_ENABLED=true to validate the PAT.");
            }
            return ValidationResult.deny(GitHubPatValidationStatus.PROVIDER_UNAVAILABLE,
                    "GitHub returned " + userResp.statusCode() + " on /user.");
        }
        if (!userResp.isOk()) {
            return ValidationResult.deny(GitHubPatValidationStatus.INVALID_TOKEN,
                    "Unexpected GitHub status " + userResp.statusCode() + " on /user.");
        }

        Map<String, Object> userBody = userResp.jsonBody();
        String username = trimToNull(userBody == null ? null : asString(userBody.get("login")));
        // PKT-FINAL-3 (BUG-03/BUG-04): autopopulate author identity from the
        // PAT owner so the operator never has to type "name" / "email" /
        // "username" by hand. Falls back to the GitHub login + a noreply
        // address when the user has no public name/email and the /user/emails
        // scope is not granted.
        String authorName = trimToNull(userBody == null ? null : asString(userBody.get("name")));
        if (authorName == null) {
            authorName = username;
        }
        String authorEmail = trimToNull(userBody == null ? null : asString(userBody.get("email")));
        if (authorEmail == null) {
            authorEmail = fetchPrimaryVerifiedEmail(token);
        }
        if (authorEmail == null && username != null) {
            // Stable, well-known GitHub noreply pattern. Commits attributed to
            // this address are recognized by GitHub as belonging to the user.
            authorEmail = username + "@users.noreply.github.com";
        }
        // PKT-FINAL-3 (CKPT-04 sub-finding): java.net.http normalizes header
        // keys to lowercase on HTTP/2, while documentation/test fixtures use
        // the canonical "X-OAuth-Scopes" spelling. Probe both so the response
        // header reliably makes it into the persisted `scopes` column.
        String scopes = headerValue(userResp.headers(), "X-OAuth-Scopes");

        if (repositoryUrl != null && !repositoryUrl.isBlank()) {
            String repoPath = repoApiPath(repositoryUrl);
            if (repoPath != null) {
                try {
                    GitHubApiClient.Response repoResp = client.get(repoPath, token);
                    if (repoResp.statusCode() == 404 || repoResp.statusCode() == 403) {
                        return ValidationResult.deny(GitHubPatValidationStatus.REPO_ACCESS_DENIED,
                                "GitHub returned " + repoResp.statusCode()
                                        + " on " + repoPath + " — token cannot access this repo.");
                    }
                    if (repoResp.statusCode() >= 500) {
                        return ValidationResult.deny(GitHubPatValidationStatus.PROVIDER_UNAVAILABLE,
                                "GitHub returned " + repoResp.statusCode() + " on " + repoPath + ".");
                    }
                } catch (RuntimeException repoErr) {
                    return ValidationResult.deny(GitHubPatValidationStatus.PROVIDER_UNAVAILABLE,
                            "GitHub repo probe failed: " + repoErr.getMessage());
                }
            }
        }

        return ValidationResult.valid(scopes, username, authorName, authorEmail);
    }

    /**
     * Best-effort fetch of the PAT owner's primary verified email via
     * {@code /user/emails}. Quietly returns null when the token does not
     * include the {@code user:email} scope (403/404) or the call fails for
     * any other reason — PAT registration must not block on email lookup.
     */
    private String fetchPrimaryVerifiedEmail(String token) {
        try {
            GitHubApiClient.Response resp = client.get(API_USER_EMAILS, token);
            if (!resp.isOk() || resp.jsonArray() == null) {
                return null;
            }
            for (Map<String, Object> entry : resp.jsonArray()) {
                Object primary = entry.get("primary");
                Object verified = entry.get("verified");
                if (Boolean.TRUE.equals(primary) && Boolean.TRUE.equals(verified)) {
                    return trimToNull(asString(entry.get("email")));
                }
            }
        } catch (RuntimeException ignored) {
            // Email lookup is best-effort; do not fail PAT validation on it.
        }
        return null;
    }

    private static String asString(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        return value.toString();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    @Override
    public PullRequestInfo createPullRequest(CreatePullRequest req) {
        String token = resolveToken(req.tokenReference());
        String path = repoApiPath(req.repositoryUrl()) + "/pulls";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", req.title());
        body.put("body", req.body());
        body.put("head", req.sourceBranch());
        body.put("base", req.targetBranch());
        GitHubApiClient.Response resp = client.post(path, token, body);
        if (!resp.isOk()) {
            throw new GitProviderException("createPullRequest failed: HTTP "
                    + resp.statusCode());
        }
        return toPullRequestInfo(req.repositoryUrl(), resp.jsonBody());
    }

    @Override
    public PullRequestInfo getPullRequest(String repositoryUrl, int prNumber, String tokenReference) {
        String token = resolveToken(tokenReference);
        String path = repoApiPath(repositoryUrl) + "/pulls/" + prNumber;
        GitHubApiClient.Response resp = client.get(path, token);
        if (!resp.isOk()) {
            throw new GitProviderException("getPullRequest failed: HTTP " + resp.statusCode());
        }
        return toPullRequestInfo(repositoryUrl, resp.jsonBody());
    }

    @Override
    public List<String> listReviewers(String repositoryUrl, String tokenReference) {
        String token = resolveToken(tokenReference);
        String path = repoApiPath(repositoryUrl) + "/collaborators";
        GitHubApiClient.Response resp = client.get(path, token);
        if (!resp.isOk() || resp.jsonArray() == null) {
            throw new GitProviderException("listReviewers failed: HTTP " + resp.statusCode());
        }
        List<String> out = new ArrayList<>(resp.jsonArray().size());
        for (Map<String, Object> row : resp.jsonArray()) {
            Object login = row.get("login");
            if (login instanceof String s && !s.isBlank()) out.add(s);
        }
        return out;
    }

    @Override
    public PullRequestInfo syncState(String repositoryUrl, int prNumber, String tokenReference) {
        return getPullRequest(repositoryUrl, prNumber, tokenReference);
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signatureHeader, String webhookSecret) {
        if (payload == null || signatureHeader == null || webhookSecret == null) return false;
        if (!signatureHeader.startsWith("sha256=")) return false;
        String expected = signatureHeader.substring("sha256=".length()).trim();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(computed.length * 2);
            for (byte b : computed) hex.append(String.format("%02x", b));
            // Constant-time compare.
            return constantTimeEquals(expected.toLowerCase(Locale.ROOT), hex.toString());
        } catch (Exception e) {
            log.warn("webhook signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /**
     * Phase 6 closeout — resolve a token reference using the FULL
     * {@code gcp-sm://projects/<projectId>/secrets/<secretId>/versions/<version>}
     * URI. The previous implementation discarded everything except the
     * secret id and re-resolved the project from a hardcoded "dev"
     * environment, which broke any tenant whose PAT lived in a
     * different GCP project (or pinned version).
     */
    private String resolveToken(String tokenReference) {
        if (tokenReference == null || tokenReference.isBlank()) {
            throw new GitProviderException("token reference is required");
        }
        try {
            return secretManager.getSecretValueByReference(tokenReference);
        } catch (IllegalArgumentException malformed) {
            throw new GitProviderException(malformed.getMessage());
        }
    }

    /** @deprecated use {@link GcpSecretManagerService.SecretReference#parse(String)}. */
    @Deprecated
    public static String extractSecretId(String reference) {
        if (reference == null || !reference.startsWith("gcp-sm://")) return null;
        String[] parts = reference.split("/");
        if (parts.length < 6) return null;
        return parts[5];
    }

    /**
     * Case-insensitive header lookup. The real client wraps response headers
     * in a {@code TreeMap(CASE_INSENSITIVE_ORDER)} so any spelling works; test
     * fixtures use plain {@code Map.of(...)} where the key may be exact-cased
     * either way. Probe canonical first, then a lowercased fallback.
     */
    private static String headerValue(Map<String, String> headers, String canonicalName) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        String value = headers.get(canonicalName);
        if (value != null) {
            return value;
        }
        String lower = canonicalName.toLowerCase(Locale.ROOT);
        value = headers.get(lower);
        if (value != null) {
            return value;
        }
        // Last-resort linear scan to catch arbitrary casing from upstream proxies.
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(canonicalName)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Convert {@code https://github.com/<owner>/<repo>(.git)?} → {@code /repos/<owner>/<repo>}. */
    public static String repoApiPath(String repositoryUrl) {
        if (repositoryUrl == null) return null;
        String url = repositoryUrl.trim();
        if (url.endsWith(".git")) url = url.substring(0, url.length() - 4);
        int idx = url.indexOf("github.com");
        if (idx < 0) return null;
        String tail = url.substring(idx + "github.com".length());
        // Strip the leading colon (for ssh form) or slash.
        while (tail.startsWith(":") || tail.startsWith("/")) tail = tail.substring(1);
        if (tail.isBlank()) return null;
        return "/repos/" + tail;
    }

    private PullRequestInfo toPullRequestInfo(String repositoryUrl, Map<String, Object> body) {
        if (body == null) {
            return new PullRequestInfo(repositoryUrl, 0, null, "OPEN",
                    null, null, null, null, null, null, List.of());
        }
        int number = body.get("number") instanceof Number n ? n.intValue() : 0;
        String state = stringOrNull(body.get("state"));
        String mergedAtRaw = stringOrNull(body.get("merged_at"));
        Instant mergedAt = mergedAtRaw == null ? null : safeParseInstant(mergedAtRaw);
        String closedAtRaw = stringOrNull(body.get("closed_at"));
        Instant closedAt = closedAtRaw == null ? null : safeParseInstant(closedAtRaw);
        String mappedState = mergedAt != null ? "MERGED"
                : "closed".equalsIgnoreCase(state) ? "CLOSED"
                : "OPEN";
        String head = nestedRef(body, "head");
        String base = nestedRef(body, "base");
        return new PullRequestInfo(
                repositoryUrl, number,
                stringOrNull(body.get("title")),
                mappedState,
                head, base,
                stringOrNull(body.get("html_url")),
                stringOrNull(body.get("merge_commit_sha")),
                mergedAt, closedAt,
                List.of());
    }

    @SuppressWarnings("unchecked")
    private static String nestedRef(Map<String, Object> body, String key) {
        Object node = body.get(key);
        if (node instanceof Map<?, ?> map) {
            Object ref = ((Map<String, Object>) map).get("ref");
            return ref == null ? null : ref.toString();
        }
        return null;
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static Instant safeParseInstant(String s) {
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    /** Thrown for non-validation provider errors (PR create, status sync, …). */
    public static class GitProviderException extends RuntimeException {
        public GitProviderException(String message) { super(message); }
    }
}
