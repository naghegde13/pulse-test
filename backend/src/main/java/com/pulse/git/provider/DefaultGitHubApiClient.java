package com.pulse.git.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Phase 6 closeout — production {@link GitHubApiClient} backed by
 * {@link java.net.http.HttpClient}.
 *
 * <p>Activated by {@code pulse.git.github.enabled=true}; default
 * {@code false} so existing dev / test environments keep using the
 * {@link StubGitHubApiClient} (which returns 503) and tests inject
 * their own deterministic stub. Tests for this class itself drive
 * the request/response mapping at the seam.
 */
@Configuration
@ConditionalOnProperty(value = "pulse.git.github.enabled", havingValue = "true")
public class DefaultGitHubApiClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultGitHubApiClient.class);
    static final String DEFAULT_BASE_URL = "https://api.github.com";
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    // PKT-FINAL-4 (BUG-35): boot-time mode log moved to GitHubClientModeAnnouncer
    // so STUB+REAL never both log when the real client is wired.

    @Bean
    @Primary
    public GitHubApiClient productionGitHubApiClient() {
        return new HttpGitHubApiClient(HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .build(), DEFAULT_BASE_URL);
    }

    /** Implementation. Package-private so tests can drive it directly with a mock HttpClient. */
    static class HttpGitHubApiClient implements GitHubApiClient {

        private final HttpClient http;
        private final String baseUrl;
        private final ObjectMapper mapper = new ObjectMapper();

        HttpGitHubApiClient(HttpClient http, String baseUrl) {
            this.http = http;
            this.baseUrl = baseUrl;
        }

        @Override
        public Response get(String path, String token) {
            HttpRequest request = baseRequest(path, token).GET().build();
            return execute(request);
        }

        @Override
        public Response post(String path, String token, Map<String, Object> body) {
            byte[] payload;
            try {
                payload = mapper.writeValueAsBytes(body == null ? Map.of() : body);
            } catch (Exception e) {
                throw new GitHubProviderAdapter.GitProviderException(
                        "Failed to encode GitHub request body: " + e.getMessage());
            }
            HttpRequest request = baseRequest(path, token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            return execute(request);
        }

        HttpRequest.Builder baseRequest(String path, String token) {
            String url = path.startsWith("http") ? path : (baseUrl + path);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(DEFAULT_TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "pulse-deployment-productization");
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            return builder;
        }

        Response execute(HttpRequest request) {
            HttpResponse<byte[]> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (java.io.IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new GitHubProviderAdapter.GitProviderException(
                        "GitHub HTTP call failed: " + e.getMessage());
            }
            // PKT-FINAL-3 (CKPT-04 sub-finding): GitHub returns OAuth scopes via the
            // `X-OAuth-Scopes` response header on `/user`, but java.net.http stores
            // header keys lowercased on HTTP/2 responses. Use a case-insensitive
            // map so adapter lookups against the canonical header name still hit.
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            response.headers().map().forEach((k, vs) -> {
                if (vs == null || vs.isEmpty()) return;
                headers.putIfAbsent(k, vs.get(0));
            });
            byte[] bodyBytes = response.body();
            String bodyText = bodyBytes == null
                    ? ""
                    : new String(bodyBytes, StandardCharsets.UTF_8);
            Map<String, Object> jsonObject = null;
            List<Map<String, Object>> jsonArray = null;
            String contentType = headers.getOrDefault("Content-Type", headers.get("content-type"));
            if (contentType != null && contentType.toLowerCase().contains("json") && !bodyText.isBlank()) {
                String trimmed = bodyText.trim();
                try {
                    if (trimmed.startsWith("[")) {
                        jsonArray = mapper.readValue(trimmed,
                                new TypeReference<List<Map<String, Object>>>() {});
                    } else {
                        jsonObject = mapper.readValue(trimmed,
                                new TypeReference<Map<String, Object>>() {});
                    }
                } catch (Exception parseErr) {
                    log.warn("GitHub response parse failed: {}", parseErr.getMessage());
                }
            }
            return new Response(response.statusCode(), headers,
                    jsonObject == null ? Map.of() : jsonObject,
                    jsonArray);
        }
    }
}
