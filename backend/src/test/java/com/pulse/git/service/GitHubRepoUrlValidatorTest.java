package com.pulse.git.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PKT-0010 — contract tests for GitHub repo URL validation.
 * Scenario 0 requires that only {@code https://github.com/} URLs are accepted;
 * local filesystem paths and non-GitHub hosts must be rejected.
 */
class GitHubRepoUrlValidatorTest {

    private final GitHubRepoUrlValidator validator = new GitHubRepoUrlValidator();

    // ---- Positive: valid GitHub URLs ----

    @Test
    void accepts_acmeLendingScenarioUrl() {
        var result = validator.validate("https://github.com/zadam2008/pulse-acme-lending.git");
        assertTrue(result.valid(), "Must accept the Scenario 0 URL");
        assertNull(result.reason());
    }

    @Test
    void accepts_githubUrlWithoutDotGit() {
        var result = validator.validate("https://github.com/zadam2008/pulse-acme-lending");
        assertTrue(result.valid());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://github.com/owner/repo.git",
            "https://github.com/my-org/my-repo.git",
            "https://github.com/user123/project_v2",
            "https://github.com/OWNER/REPO.git"
    })
    void accepts_validGithubUrls(String url) {
        assertTrue(validator.validate(url).valid(), "Should accept: " + url);
    }

    // ---- Negative: local filesystem paths ----

    @ParameterizedTest
    @ValueSource(strings = {
            "/data/pulse/repos/acme",
            "file:///data/pulse/repos/acme",
            "./relative/path",
            "../parent/path"
    })
    void rejects_localFilesystemPaths(String path) {
        var result = validator.validate(path);
        assertFalse(result.valid(), "Must reject local path: " + path);
        assertNotNull(result.reason());
        assertTrue(result.reason().toLowerCase().contains("local"),
                "Reason should mention 'local': " + result.reason());
    }

    // ---- Negative: SSH URIs ----

    @ParameterizedTest
    @ValueSource(strings = {
            "git@github.com:zadam2008/pulse-acme-lending.git",
            "ssh://git@github.com/zadam2008/pulse-acme-lending.git"
    })
    void rejects_sshUris(String url) {
        var result = validator.validate(url);
        assertFalse(result.valid(), "Must reject SSH URI: " + url);
        assertTrue(result.reason().toLowerCase().contains("ssh"));
    }

    // ---- Negative: non-GitHub hosts ----

    @ParameterizedTest
    @ValueSource(strings = {
            "https://gitlab.com/owner/repo.git",
            "https://bitbucket.org/owner/repo.git",
            "https://internal.corp.com/owner/repo.git"
    })
    void rejects_nonGithubHosts(String url) {
        var result = validator.validate(url);
        assertFalse(result.valid(), "Must reject non-GitHub host: " + url);
        assertTrue(result.reason().contains("github.com"));
    }

    // ---- Negative: null/blank ----

    @Test
    void rejects_null() {
        assertFalse(validator.validate(null).valid());
    }

    @Test
    void rejects_blank() {
        assertFalse(validator.validate("").valid());
        assertFalse(validator.validate("   ").valid());
    }

    // ---- Negative: malformed ----

    @Test
    void rejects_httpNonSecure() {
        var result = validator.validate("http://github.com/owner/repo.git");
        assertFalse(result.valid());
    }

    @Test
    void rejects_githubUrlWithoutOwnerOrRepo() {
        assertFalse(validator.validate("https://github.com/").valid());
        assertFalse(validator.validate("https://github.com/owner-only").valid());
    }
}
