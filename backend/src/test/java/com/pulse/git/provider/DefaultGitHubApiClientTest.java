package com.pulse.git.provider;

import com.pulse.git.provider.DefaultGitHubApiClient;
import com.pulse.git.provider.GitHubApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 6 closeout — production {@link GitHubApiClient} backed by
 * {@link HttpClient}. Tests drive the seam with a mocked HttpClient
 * so they're deterministic; no live GitHub calls.
 */
class DefaultGitHubApiClientTest {

    private static HttpResponse<byte[]> response(int status, String contentType, String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body == null
                ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        Map<String, List<String>> headerMap = contentType == null
                ? Map.of()
                : Map.of("Content-Type", List.of(contentType));
        HttpHeaders headers = HttpHeaders.of(headerMap, (k, v) -> true);
        when(resp.headers()).thenReturn(headers);
        return resp;
    }

    @Test
    @DisplayName("GET /user sets Authorization, Accept, X-GitHub-Api-Version, User-Agent headers")
    void getRequestSetsRequiredHeaders() throws Exception {
        HttpClient http = mock(HttpClient.class);
        org.mockito.Mockito.doReturn(
                response(200, "application/json; charset=utf-8", "{\"login\":\"mrivera\"}"))
                .when(http).send(any(HttpRequest.class), any());
        var client = new DefaultGitHubApiClient.HttpGitHubApiClient(http,
                "https://api.github.com");

        GitHubApiClient.Response result = client.get("/user", "ghp_x");
        ArgumentCaptor<HttpRequest> reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(reqCaptor.capture(), any());
        HttpRequest sent = reqCaptor.getValue();
        assertEquals(URI.create("https://api.github.com/user"), sent.uri());
        assertEquals("GET", sent.method());
        assertEquals(List.of("Bearer ghp_x"),
                sent.headers().allValues("Authorization"));
        assertEquals(List.of("application/vnd.github+json"),
                sent.headers().allValues("Accept"));
        assertEquals(List.of("2022-11-28"),
                sent.headers().allValues("X-GitHub-Api-Version"));
        assertTrue(sent.headers().firstValue("User-Agent").orElse("")
                        .contains("pulse-deployment-productization"),
                "User-Agent must identify Pulse");

        assertEquals(200, result.statusCode());
        assertEquals("mrivera", result.jsonBody().get("login"));
    }

    @Test
    @DisplayName("POST encodes JSON body with Content-Type and forwards status + parsed body")
    void postRequestEncodesJsonBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        org.mockito.Mockito.doReturn(
                response(201, "application/json", "{\"number\":42,\"state\":\"open\"}"))
                .when(http).send(any(HttpRequest.class), any());
        var client = new DefaultGitHubApiClient.HttpGitHubApiClient(http,
                "https://api.github.com");

        GitHubApiClient.Response result = client.post("/repos/acme/loans/pulls", "ghp_x",
                Map.of("title", "test"));
        ArgumentCaptor<HttpRequest> reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(reqCaptor.capture(), any());
        HttpRequest sent = reqCaptor.getValue();
        assertEquals("POST", sent.method());
        assertEquals(List.of("application/json"),
                sent.headers().allValues("Content-Type"));
        assertNotNull(sent.bodyPublisher().orElse(null));

        assertEquals(201, result.statusCode());
        assertEquals(42, result.jsonBody().get("number"));
        assertEquals("open", result.jsonBody().get("state"));
    }

    @Test
    @DisplayName("Network exception surfaces as GitProviderException, not raw IOException")
    void networkErrorWrappedInGitProviderException() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any()))
                .thenThrow(new java.io.IOException("ECONNRESET"));
        var client = new DefaultGitHubApiClient.HttpGitHubApiClient(http,
                "https://api.github.com");

        Throwable thrown = org.junit.jupiter.api.Assertions.assertThrows(
                com.pulse.git.provider.GitHubProviderAdapter.GitProviderException.class,
                () -> client.get("/user", "ghp_x"));
        assertTrue(thrown.getMessage().contains("ECONNRESET"));
    }

    @Test
    @DisplayName("JSON array response populates jsonArray rather than jsonBody")
    void jsonArrayResponseParsesIntoJsonArray() throws Exception {
        HttpClient http = mock(HttpClient.class);
        org.mockito.Mockito.doReturn(
                response(200, "application/json",
                        "[{\"login\":\"alice\"},{\"login\":\"bob\"}]"))
                .when(http).send(any(HttpRequest.class), any());
        var client = new DefaultGitHubApiClient.HttpGitHubApiClient(http,
                "https://api.github.com");

        GitHubApiClient.Response result = client.get("/repos/acme/loans/collaborators", "ghp_x");
        assertNotNull(result.jsonArray());
        assertEquals(2, result.jsonArray().size());
        assertEquals("alice", result.jsonArray().get(0).get("login"));
        assertEquals("bob", result.jsonArray().get(1).get("login"));
    }
}
