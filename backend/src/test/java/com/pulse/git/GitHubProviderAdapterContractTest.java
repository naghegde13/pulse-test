package com.pulse.git;

import com.pulse.git.identity.GitHubPatValidationStatus;
import com.pulse.git.provider.GitHubApiClient;
import com.pulse.git.provider.GitHubProviderAdapter;
import com.pulse.git.provider.GitProviderAdapter;
import com.pulse.secret.service.GcpSecretManagerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 6 — GitHubProviderAdapter contract.
 *
 * <p>Drives the adapter through a mocked {@link GitHubApiClient} so
 * tests are deterministic and never make real network calls. Pins
 * status mapping (HTTP → {@link GitHubPatValidationStatus}), repo
 * URL parsing, PR JSON → {@link GitProviderAdapter.PullRequestInfo}
 * mapping, and webhook signature verification.
 */
class GitHubProviderAdapterContractTest {

    private GitHubApiClient client;
    private GcpSecretManagerService secretManager;
    private GitHubProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(GitHubApiClient.class);
        secretManager = mock(GcpSecretManagerService.class);
        // Phase 6 closeout: adapter resolves token references through
        // the full-URI path; existing tests use a stub reference.
        when(secretManager.getSecretValueByReference(anyString())).thenReturn("ghp_resolved");
        adapter = new GitHubProviderAdapter(client, secretManager);
    }

    @Test
    @DisplayName("HTTP 200 on /user → VALID with login + scopes")
    void httpOkReturnsValid() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("login", "mrivera");
        when(client.get(eq("/user"), eq("ghp_x"))).thenReturn(
                new GitHubApiClient.Response(200,
                        Map.of("X-OAuth-Scopes", "repo, read:user"), body, null));
        var result = adapter.validateToken("ghp_x", null);
        assertEquals(GitHubPatValidationStatus.VALID, result.status());
        assertEquals("mrivera", result.githubUsername());
        assertEquals("repo, read:user", result.scopes());
    }

    @Test
    @DisplayName("HTTP 401 on /user → INVALID_TOKEN")
    void http401ReturnsInvalidToken() {
        when(client.get(eq("/user"), anyString())).thenReturn(
                new GitHubApiClient.Response(401, Map.of(), Map.of(), null));
        var result = adapter.validateToken("ghp_bad", null);
        assertEquals(GitHubPatValidationStatus.INVALID_TOKEN, result.status());
    }

    @Test
    @DisplayName("HTTP 403 on /user → INSUFFICIENT_SCOPE")
    void http403ReturnsInsufficientScope() {
        when(client.get(eq("/user"), anyString())).thenReturn(
                new GitHubApiClient.Response(403, Map.of(), Map.of(), null));
        var result = adapter.validateToken("ghp_x", null);
        assertEquals(GitHubPatValidationStatus.INSUFFICIENT_SCOPE, result.status());
    }

    @Test
    @DisplayName("HTTP 5xx on /user → PROVIDER_UNAVAILABLE (real-mode message)")
    void http5xxReturnsProviderUnavailable() {
        when(client.get(eq("/user"), anyString())).thenReturn(
                new GitHubApiClient.Response(503, Map.of(), Map.of(), null));
        // PKT-FINAL-3 (BUG-08): when the real client is enabled, the message
        // attributes the failure to GitHub.
        var realModeAdapter = new GitHubProviderAdapter(client, secretManager, /* enabled = */ true);
        var result = realModeAdapter.validateToken("ghp_x", null);
        assertEquals(GitHubPatValidationStatus.PROVIDER_UNAVAILABLE, result.status());
        assertTrue(result.message().contains("GitHub returned 503"));
    }

    @Test
    @DisplayName("PKT-FINAL-3 BUG-08: stub mode produces stub-attribution error message, not 'GitHub returned'")
    void http5xxInStubModeAttributesToStub() {
        when(client.get(eq("/user"), anyString())).thenReturn(
                new GitHubApiClient.Response(503, Map.of(), Map.of(), null));
        // Default-constructed adapter has githubEnabled=false (stub mode).
        var stubModeAdapter = new GitHubProviderAdapter(client, secretManager);
        var result = stubModeAdapter.validateToken("ghp_x", null);
        assertEquals(GitHubPatValidationStatus.PROVIDER_UNAVAILABLE, result.status());
        assertTrue(result.message().contains("stub mode"),
                "Expected 'stub mode' attribution, got: " + result.message());
        assertTrue(result.message().contains("pulse.git.github.enabled=false"),
                "Expected toggle hint, got: " + result.message());
    }

    @Test
    @DisplayName("PKT-FINAL-3 BUG-03/04: author name/email auto-populated from /user when present")
    void authorIdentityPopulatedFromUserResponse() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("login", "zadam");
        body.put("name", "Z Adam");
        body.put("email", "z@example.com");
        when(client.get(eq("/user"), anyString())).thenReturn(
                new GitHubApiClient.Response(200,
                        Map.of("X-OAuth-Scopes", "repo"), body, null));
        var result = adapter.validateToken("ghp_x", null);
        assertEquals("zadam", result.githubUsername());
        assertEquals("Z Adam", result.authorName());
        assertEquals("z@example.com", result.authorEmail());
    }

    @Test
    @DisplayName("PKT-FINAL-3 BUG-03/04: falls back to /user/emails primary verified entry when /user.email is null")
    void authorEmailFallsBackToEmailsEndpoint() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("login", "zadam");
        // /user has no email (private profile).
        body.put("name", "Z Adam");
        body.put("email", null);
        when(client.get(eq("/user"), anyString())).thenReturn(
                new GitHubApiClient.Response(200, Map.of(), body, null));
        java.util.List<Map<String, Object>> emails = java.util.List.of(
                Map.of("email", "secondary@example.com", "primary", false, "verified", true),
                Map.of("email", "primary@example.com", "primary", true, "verified", true));
        when(client.get(eq("/user/emails"), anyString())).thenReturn(
                new GitHubApiClient.Response(200, Map.of(), Map.of(), emails));
        var result = adapter.validateToken("ghp_x", null);
        assertEquals("primary@example.com", result.authorEmail());
    }

    @Test
    @DisplayName("PKT-FINAL-3 BUG-03/04: falls back to noreply email when /user/emails is denied (insufficient scope)")
    void authorEmailFallsBackToGithubNoreply() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("login", "zadam");
        body.put("name", "Z Adam");
        body.put("email", null);
        when(client.get(eq("/user"), anyString())).thenReturn(
                new GitHubApiClient.Response(200, Map.of(), body, null));
        // /user/emails returns 404 (scope missing).
        when(client.get(eq("/user/emails"), anyString())).thenReturn(
                new GitHubApiClient.Response(404, Map.of(), Map.of(), null));
        var result = adapter.validateToken("ghp_x", null);
        assertEquals("zadam@users.noreply.github.com", result.authorEmail());
    }

    @Test
    @DisplayName("PKT-FINAL-3 CKPT-04: X-OAuth-Scopes header parsed even with lowercase key")
    void scopesHeaderParsedCaseInsensitively() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("login", "zadam");
        // Simulate java.net.http lowercased headers from the real client.
        when(client.get(eq("/user"), anyString())).thenReturn(
                new GitHubApiClient.Response(200,
                        Map.of("x-oauth-scopes", "repo, read:user"), body, null));
        var result = adapter.validateToken("ghp_x", null);
        assertEquals(GitHubPatValidationStatus.VALID, result.status());
        assertEquals("repo, read:user", result.scopes());
    }

    @Test
    @DisplayName("Network exception → PROVIDER_UNAVAILABLE")
    void networkErrorReturnsProviderUnavailable() {
        when(client.get(eq("/user"), anyString())).thenThrow(new RuntimeException("ECONNRESET"));
        var result = adapter.validateToken("ghp_x", null);
        assertEquals(GitHubPatValidationStatus.PROVIDER_UNAVAILABLE, result.status());
    }

    @Test
    @DisplayName("Repo probe 404 → REPO_ACCESS_DENIED")
    void repoProbe404ReturnsRepoAccessDenied() {
        when(client.get(eq("/user"), anyString())).thenReturn(
                new GitHubApiClient.Response(200, Map.of(), Map.of("login", "mrivera"), null));
        when(client.get(eq("/repos/acme/loans"), anyString())).thenReturn(
                new GitHubApiClient.Response(404, Map.of(), Map.of(), null));
        var result = adapter.validateToken("ghp_x", "https://github.com/acme/loans.git");
        assertEquals(GitHubPatValidationStatus.REPO_ACCESS_DENIED, result.status());
    }

    @Test
    @DisplayName("repoApiPath strips .git and ssh/https prefixes")
    void repoApiPathParses() {
        assertEquals("/repos/acme/loans",
                GitHubProviderAdapter.repoApiPath("https://github.com/acme/loans.git"));
        assertEquals("/repos/acme/loans",
                GitHubProviderAdapter.repoApiPath("https://github.com/acme/loans"));
        assertEquals("/repos/acme/loans",
                GitHubProviderAdapter.repoApiPath("git@github.com:acme/loans.git"));
        assertEquals(null, GitHubProviderAdapter.repoApiPath("https://gitlab.com/acme/loans"));
    }

    @Test
    @DisplayName("createPullRequest maps GitHub JSON → PullRequestInfo")
    void createPullRequestMapsResponse() {
        Map<String, Object> head = Map.of("ref", "feature/x");
        Map<String, Object> base = Map.of("ref", "main");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("number", 42);
        body.put("title", "Test PR");
        body.put("state", "open");
        body.put("html_url", "https://github.com/acme/loans/pull/42");
        body.put("head", head);
        body.put("base", base);
        when(client.post(eq("/repos/acme/loans/pulls"), eq("ghp_resolved"), any())).thenReturn(
                new GitHubApiClient.Response(201, Map.of(), body, null));

        GitProviderAdapter.PullRequestInfo info = adapter.createPullRequest(
                new GitProviderAdapter.CreatePullRequest(
                        "https://github.com/acme/loans",
                        "Test PR", "body", "feature/x", "main",
                        "gcp-sm://projects/pulse-dev/secrets/sec-id/versions/latest",
                        List.of()));
        assertEquals(42, info.number());
        assertEquals("OPEN", info.state());
        assertEquals("feature/x", info.sourceBranch());
        assertEquals("main", info.targetBranch());
        assertEquals("https://github.com/acme/loans/pull/42", info.url());
    }

    @Test
    @DisplayName("getPullRequest maps merged_at → MERGED state")
    void getPullRequestMapsMergedState() {
        Map<String, Object> head = Map.of("ref", "feature/x");
        Map<String, Object> base = Map.of("ref", "main");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("number", 42);
        body.put("state", "closed");
        body.put("merged_at", "2026-05-04T00:00:00Z");
        body.put("merge_commit_sha", "deadbeef");
        body.put("head", head);
        body.put("base", base);
        when(client.get(eq("/repos/acme/loans/pulls/42"), eq("ghp_resolved"))).thenReturn(
                new GitHubApiClient.Response(200, Map.of(), body, null));

        GitProviderAdapter.PullRequestInfo info = adapter.getPullRequest(
                "https://github.com/acme/loans", 42,
                "gcp-sm://projects/pulse-dev/secrets/sec/versions/latest");
        assertEquals("MERGED", info.state());
        assertEquals("deadbeef", info.mergeCommitSha());
        assertNotNull(info.mergedAt());
    }

    @Test
    @DisplayName("verifyWebhookSignature accepts a correct sha256= signature")
    void verifyWebhookSignatureAcceptsCorrectHmac() throws Exception {
        String payload = "{\"action\":\"opened\"}";
        String secret = "shhh";
        // Compute the expected signature externally so the test isn't a tautology.
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : expected) hex.append(String.format("%02x", b));
        String header = "sha256=" + hex;
        assertTrue(adapter.verifyWebhookSignature(payload, header, secret));
        assertFalse(adapter.verifyWebhookSignature(payload, "sha256=" + "0".repeat(64), secret));
        assertFalse(adapter.verifyWebhookSignature(payload, "md5=whatever", secret));
        assertFalse(adapter.verifyWebhookSignature(payload, null, secret));
    }
}
