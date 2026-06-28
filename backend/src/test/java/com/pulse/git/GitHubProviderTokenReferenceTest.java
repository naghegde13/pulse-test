package com.pulse.git;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 6 closeout — GitHubProviderAdapter MUST resolve token
 * references via {@link GcpSecretManagerService#getSecretValueByReference},
 * which honors the full {@code projects/<p>/secrets/<s>/versions/<v>}
 * path. The previous implementation discarded everything except the
 * secret id and re-resolved the project from a hardcoded "dev"
 * environment, breaking any non-dev tenant.
 */
class GitHubProviderTokenReferenceTest {

    private GitHubApiClient client;
    private GcpSecretManagerService secretManager;
    private GitHubProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(GitHubApiClient.class);
        secretManager = mock(GcpSecretManagerService.class);
        adapter = new GitHubProviderAdapter(client, secretManager);
    }

    @Test
    @DisplayName("createPullRequest passes the FULL gcp-sm reference to the secret manager — not just the secret id")
    void createPullRequestUsesFullReference() {
        String fullRef = "gcp-sm://projects/pulse-prod-001/secrets/my-pat/versions/7";
        when(secretManager.getSecretValueByReference(fullRef)).thenReturn("ghp_resolved");
        when(client.post(eq("/repos/acme/loans/pulls"), eq("ghp_resolved"), any())).thenReturn(
                new GitHubApiClient.Response(201, Map.of(), Map.of(
                        "number", 1,
                        "state", "open",
                        "head", Map.of("ref", "feature/x"),
                        "base", Map.of("ref", "main")), null));

        adapter.createPullRequest(new GitProviderAdapter.CreatePullRequest(
                "https://github.com/acme/loans",
                "title", "body", "feature/x", "main", fullRef, List.of()));

        // The full reference must flow through unchanged.
        verify(secretManager).getSecretValueByReference(fullRef);
        // The legacy env-based read path must NOT be called.
        verify(secretManager, never()).getSecretValue(anyString(), anyString());
    }

    @Test
    @DisplayName("getPullRequest also routes through getSecretValueByReference")
    void getPullRequestUsesFullReference() {
        String fullRef = "gcp-sm://projects/pulse-uat-099/secrets/another-pat/versions/latest";
        when(secretManager.getSecretValueByReference(fullRef)).thenReturn("ghp_uat");
        when(client.get(eq("/repos/acme/loans/pulls/42"), eq("ghp_uat"))).thenReturn(
                new GitHubApiClient.Response(200, Map.of(), Map.of(
                        "number", 42,
                        "state", "open",
                        "head", Map.of("ref", "feature/x"),
                        "base", Map.of("ref", "main")), null));

        adapter.getPullRequest("https://github.com/acme/loans", 42, fullRef);

        verify(secretManager).getSecretValueByReference(fullRef);
        verify(secretManager, never()).getSecretValue(anyString(), anyString());
    }

    @Test
    @DisplayName("Different project ids on different references stay distinct end-to-end")
    void differentProjectIdsStayDistinct() {
        String prodRef = "gcp-sm://projects/pulse-prod-001/secrets/p/versions/latest";
        String integRef = "gcp-sm://projects/pulse-integration-002/secrets/i/versions/latest";
        when(secretManager.getSecretValueByReference(prodRef)).thenReturn("ghp_prod");
        when(secretManager.getSecretValueByReference(integRef)).thenReturn("ghp_integ");
        when(client.get(any(), eq("ghp_prod"))).thenReturn(
                new GitHubApiClient.Response(200, Map.of(), Map.of(
                        "number", 1, "state", "open",
                        "head", Map.of("ref", "x"), "base", Map.of("ref", "main")), null));
        when(client.get(any(), eq("ghp_integ"))).thenReturn(
                new GitHubApiClient.Response(200, Map.of(), Map.of(
                        "number", 2, "state", "open",
                        "head", Map.of("ref", "y"), "base", Map.of("ref", "main")), null));

        adapter.getPullRequest("https://github.com/acme/loans", 1, prodRef);
        adapter.getPullRequest("https://github.com/acme/loans", 2, integRef);

        verify(secretManager).getSecretValueByReference(prodRef);
        verify(secretManager).getSecretValueByReference(integRef);
        verify(client).get(any(), eq("ghp_prod"));
        verify(client).get(any(), eq("ghp_integ"));
    }
}
