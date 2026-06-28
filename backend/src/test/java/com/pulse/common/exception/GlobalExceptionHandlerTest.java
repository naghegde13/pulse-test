package com.pulse.common.exception;

import com.pulse.git.service.GitAuthenticationException;
import com.pulse.git.service.GitOnboardingException;
import com.pulse.git.service.GitRepoAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PKT-FINAL-4 (BUG-41 + BUG-36): the Git exception handlers moved here
 * from GitController so the response envelope walks the cause chain.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleGitAuth_chainedCauseSurfacedInMessage() {
        // The handler must surface the underlying JGit cause in the `message`
        // field so the UI envelope distinguishes auth-rejection from
        // empty-repo from network failure.
        Throwable jgitCause = new Exception("Remote repository is empty");
        GitAuthenticationException ex = new GitAuthenticationException(
                "Git operation failed for https://example.com/repo", jgitCause);

        ResponseEntity<Map<String, Object>> response = handler.handleGitAuth(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody().get("code")).isEqualTo("UPSTREAM_GIT_AUTH_FAILED");
        assertThat(response.getBody().get("type")).isEqualTo("UPSTREAM_AUTH");
        String message = (String) response.getBody().get("message");
        assertThat(message).contains("Git operation failed for https://example.com/repo");
        assertThat(message).contains("Remote repository is empty");
    }

    @Test
    void handleGitAuth_nullMessage_fallsBackToFriendlyMessage() {
        ResponseEntity<Map<String, Object>> response = handler.handleGitAuth(
                new GitAuthenticationException(null));
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody().get("message")).isEqualTo("Git credential verification failed");
    }

    @Test
    void handleGitAuth_baseEnvelopePreserved_pktFinal3Contract() {
        // PKT-FINAL-3's contract (502 + code:UPSTREAM_GIT_AUTH_FAILED +
        // type:UPSTREAM_AUTH) must survive the BUG-41 cause-chain change.
        ResponseEntity<Map<String, Object>> response = handler.handleGitAuth(
                new GitAuthenticationException("Bad PAT for origin"));
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody().get("code")).isEqualTo("UPSTREAM_GIT_AUTH_FAILED");
        assertThat(response.getBody().get("type")).isEqualTo("UPSTREAM_AUTH");
        assertThat(response.getBody().get("message")).isEqualTo("Bad PAT for origin");
    }

    @Test
    void handleGitRepoAccessDenied_walksCauseChain() {
        Throwable cause = new Exception("HTTP 403: insufficient repo scope");
        GitRepoAccessDeniedException ex = new GitRepoAccessDeniedException(
                "Repo locked for org/repo", cause);

        ResponseEntity<Map<String, Object>> response = handler.handleGitRepoAccessDenied(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().get("code")).isEqualTo("UPSTREAM_GIT_REPO_ACCESS_DENIED");
        assertThat(response.getBody().get("type")).isEqualTo("UPSTREAM_AUTH");
        String message = (String) response.getBody().get("message");
        assertThat(message).contains("Repo locked for org/repo");
        assertThat(message).contains("HTTP 403: insufficient repo scope");
    }

    @Test
    void handleGitOnboarding_preservesMessage() {
        ProblemDetail problem = handler.handleGitOnboarding(
                new GitOnboardingException(
                        "Failed to create clone target /data/pulse/repos/home-lending. "
                                + "Set PULSE_GIT_CLONE_BASE to a writable path and restart the backend."));
        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getDetail()).contains("PULSE_GIT_CLONE_BASE");
        assertThat(problem.getProperties()).containsEntry("code", "GIT_ONBOARDING_ERROR");
    }

    @Test
    void handleGitOnboarding_nullMessage_friendlyFallback() {
        ProblemDetail problem = handler.handleGitOnboarding(new GitOnboardingException(null));
        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getDetail()).isEqualTo("Git onboarding operation failed");
    }

    @Test
    void walkCauseChain_capsAt1024Chars() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2000; i++) big.append('x');
        String result = GlobalExceptionHandler.walkCauseChain(
                new RuntimeException("base", new RuntimeException(big.toString())),
                "base");
        assertThat(result.length()).isLessThanOrEqualTo(1024);
    }
}
