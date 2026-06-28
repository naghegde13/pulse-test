package com.pulse.git.provider;

import java.util.List;
import java.util.Map;

/**
 * Phase 6 — minimal HTTP-shaped contract over the GitHub REST surface.
 * Tests inject a deterministic implementation; production wires
 * {@code DefaultGitHubApiClient} (a thin {@code java.net.http.HttpClient}
 * wrapper) at startup.
 *
 * <p>Returning a typed {@link Response} (not a string) keeps status
 * inspection trivial without dragging the GitHub SDK / Apache HC into
 * Pulse's dependency tree.
 */
public interface GitHubApiClient {

    /** Issue an authenticated GET; {@code path} is GitHub-relative (e.g. {@code /user}). */
    Response get(String path, String token);

    /** Issue an authenticated POST with a JSON body. */
    Response post(String path, String token, Map<String, Object> body);

    /** Generic response envelope. */
    record Response(
            int statusCode,
            Map<String, String> headers,
            Map<String, Object> jsonBody,
            List<Map<String, Object>> jsonArray
    ) {
        public boolean isOk() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
