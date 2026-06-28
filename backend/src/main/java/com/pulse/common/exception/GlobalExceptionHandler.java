package com.pulse.common.exception;

import com.pulse.blueprint.exception.BlueprintCompatReadOnlyException;
import com.pulse.blueprint.exception.BlueprintNotAddableException;
import com.pulse.git.service.GitAuthenticationException;
import com.pulse.git.service.GitOnboardingException;
import com.pulse.git.service.GitRepoAccessDeniedException;
import com.pulse.runtime.service.RuntimeAuthorityService.RuntimeAuthorityViolationException;
import com.pulse.secret.service.SecretManagerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource Not Found");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(RuntimeAuthorityViolationException.class)
    public ProblemDetail handleRuntimeAuthorityViolation(RuntimeAuthorityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Runtime Authority Violation");
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("errorCode", "RUNTIME_AUTHORITY_VIOLATION");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // ARCH-014: deprecated/deferred mutation -> 422 with stable code.
    @ExceptionHandler(BlueprintCompatReadOnlyException.class)
    public ProblemDetail handleCompatReadOnly(BlueprintCompatReadOnlyException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Blueprint Compatibility Read Only");
        problem.setProperty("code", "BLUEPRINT_COMPAT_READ_ONLY");
        problem.setProperty("blueprintKey", ex.getBlueprintKey());
        problem.setProperty("replacementBlueprintKey", ex.getReplacementBlueprintKey());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // ARCH-011: non-composition blueprint targeted by add/configure -> 422.
    @ExceptionHandler(BlueprintNotAddableException.class)
    public ProblemDetail handleNotAddable(BlueprintNotAddableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Blueprint Not Addable");
        problem.setProperty("code", "BLUEPRINT_NOT_ADDABLE");
        problem.setProperty("blueprintKey", ex.getBlueprintKey());
        problem.setProperty("addSurface", ex.getAddSurface());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // Pass through ResponseStatusException with its actual status + reason instead of letting
    // the catch-all Exception handler rewrite it to 500. Previously AuthorizationPolicyService's
    // 403 FORBIDDEN denials surfaced to clients as opaque 500 Internal Server Error responses.
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        HttpStatusCode statusCode = ex.getStatusCode();
        String reason = ex.getReason();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                statusCode, reason != null ? reason : HttpStatus.valueOf(statusCode.value()).getReasonPhrase());
        problem.setTitle(HttpStatus.valueOf(statusCode.value()).getReasonPhrase());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void handleAsyncDisconnect(org.springframework.web.context.request.async.AsyncRequestNotUsableException ex) {
        // Client disconnected from SSE stream -- silently ignore
    }

    /**
     * PKT-FINAL-3 (BUG-07): preserve the informative {@link SecretManagerException}
     * message (e.g. "/data: Read-only file system") instead of letting it fall
     * through to {@link #handleGeneral} which replaces every detail with
     * "An unexpected error occurred". The frontend's PAT-registration toast
     * now surfaces the original cause so the operator can act on it.
     */
    @ExceptionHandler(SecretManagerException.class)
    public ProblemDetail handleSecretManager(SecretManagerException ex) {
        log.error("Secret Manager operation failed", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ex.getMessage() != null ? ex.getMessage() : "Secret Manager operation failed");
        problem.setTitle("Secret Manager Error");
        problem.setProperty("code", "SECRET_MANAGER_ERROR");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * PKT-FINAL-4 (BUG-36): typed handler for non-credential tenant Git
     * onboarding failures (e.g. unwritable clone target, failed init
     * fallback). Preserves the actionable message so the operator can
     * fix the configuration without parsing logs.
     */
    @ExceptionHandler(GitOnboardingException.class)
    public ProblemDetail handleGitOnboarding(GitOnboardingException ex) {
        log.error("Git onboarding operation failed", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ex.getMessage() != null ? ex.getMessage() : "Git onboarding operation failed");
        problem.setTitle("Git Onboarding Error");
        problem.setProperty("code", "GIT_ONBOARDING_ERROR");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * PKT-FINAL-4 (BUG-41): the prior handler lived on {@code GitController}
     * and only echoed {@code ex.getMessage()}. Moving it here lets us
     * walk the full {@code Caused by:} chain so the operator-visible
     * envelope distinguishes auth-rejection from empty-repo from
     * network failure. The HTTP contract (502 + {@code code} /
     * {@code type} / {@code message}) is unchanged.
     */
    @ExceptionHandler(GitAuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleGitAuth(GitAuthenticationException ex) {
        log.error("Git authentication failed", ex);
        String detail = walkCauseChain(ex,
                ex.getMessage() != null ? ex.getMessage() : "Git credential verification failed");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                        "code", "UPSTREAM_GIT_AUTH_FAILED",
                        "type", "UPSTREAM_AUTH",
                        "message", detail));
    }

    /**
     * PKT-FINAL-4 (BUG-41): mirrors {@link #handleGitAuth} for the 403
     * envelope used when GitHub rejects access to the repository (the
     * caller IS authenticated but lacks permission on the resource).
     */
    @ExceptionHandler(GitRepoAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleGitRepoAccessDenied(GitRepoAccessDeniedException ex) {
        log.error("Git repository access denied", ex);
        String detail = walkCauseChain(ex,
                ex.getMessage() != null ? ex.getMessage() : "Git repository access denied");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of(
                        "code", "UPSTREAM_GIT_REPO_ACCESS_DENIED",
                        "type", "UPSTREAM_AUTH",
                        "message", detail));
    }

    /**
     * PKT-FINAL-4 (BUG-41): append every non-blank cause message in the
     * chain, capped at 1024 chars so the envelope cannot overflow.
     */
    static String walkCauseChain(Throwable ex, String baseMessage) {
        StringBuilder detail = new StringBuilder(baseMessage);
        Throwable cause = ex.getCause();
        while (cause != null) {
            String msg = cause.getMessage();
            if (msg != null && !msg.isBlank() && !baseMessage.contains(msg)) {
                detail.append(" — caused by: ").append(msg);
            }
            cause = cause.getCause();
        }
        String result = detail.toString();
        if (result.length() > 1024) {
            result = result.substring(0, 1020) + "...";
        }
        return result;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setTitle("Internal Server Error");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
