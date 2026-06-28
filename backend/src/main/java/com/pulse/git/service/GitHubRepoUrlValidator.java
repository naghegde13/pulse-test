package com.pulse.git.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Validates GitHub repository URLs for Scenario 0 tenant repo linkage.
 * Accepts only {@code https://github.com/} URLs; rejects local filesystem
 * paths, non-GitHub hosts, and SSH URIs.
 */
@Component
public class GitHubRepoUrlValidator {

    private static final Pattern GITHUB_HTTPS_PATTERN =
            Pattern.compile("^https://github\\.com/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+(\\.git)?$");

    /**
     * Validate that the given URL is a well-formed GitHub HTTPS repository URL.
     *
     * @param repoUrl the URL to validate
     * @return a {@link Result} indicating validity and reason
     */
    public Result validate(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return Result.invalid("repoUrl is required");
        }

        String trimmed = repoUrl.strip();

        // Reject local filesystem paths
        if (trimmed.startsWith("/") || trimmed.startsWith("file://")
                || trimmed.startsWith("./") || trimmed.startsWith("..")) {
            return Result.invalid(
                    "Local filesystem paths are not accepted for tenant repo linkage; "
                            + "use a GitHub HTTPS URL (https://github.com/<owner>/<repo>.git)");
        }

        // Reject SSH URIs
        if (trimmed.startsWith("git@") || trimmed.startsWith("ssh://")) {
            return Result.invalid(
                    "SSH URIs are not supported; use HTTPS (https://github.com/<owner>/<repo>.git)");
        }

        // Reject non-HTTPS schemes
        if (!trimmed.startsWith("https://")) {
            return Result.invalid(
                    "Only HTTPS GitHub URLs are accepted (https://github.com/<owner>/<repo>.git)");
        }

        // Reject non-GitHub hosts
        try {
            URI uri = URI.create(trimmed);
            if (!"github.com".equalsIgnoreCase(uri.getHost())) {
                return Result.invalid(
                        "Only github.com repositories are accepted for Scenario 0; "
                                + "host was: " + uri.getHost());
            }
        } catch (IllegalArgumentException e) {
            return Result.invalid("Malformed URL: " + e.getMessage());
        }

        // Validate structure: github.com/<owner>/<repo>[.git]
        if (!GITHUB_HTTPS_PATTERN.matcher(trimmed).matches()) {
            return Result.invalid(
                    "URL must match https://github.com/<owner>/<repo>.git");
        }

        return Result.ok();
    }

    public record Result(boolean valid, String reason) {
        static Result ok() { return new Result(true, null); }
        static Result invalid(String reason) { return new Result(false, reason); }
    }
}
