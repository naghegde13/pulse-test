package com.pulse.git.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Phase 6 default {@link GitHubApiClient} bean. Production should
 * replace this with a real HTTP-backed client (java.net.http) once
 * outbound GitHub network egress is approved. Until then, every call
 * returns a {@code 503} response so any accidental real call surfaces
 * as {@code PROVIDER_UNAVAILABLE} instead of silently succeeding.
 *
 * <p>Tests inject their own {@link GitHubApiClient} directly via
 * constructor, so this stub never appears in test paths.
 *
 * <p>PKT-FINAL-3 (BUG-08): boot-time mode announcement used to fire
 * unconditionally from a {@code @PostConstruct} on this class.
 * PKT-FINAL-4 (BUG-35) moved that signal to
 * {@link GitHubClientModeAnnouncer}, which reads
 * {@code pulse.git.github.enabled} once at context refresh so the log
 * always matches the actually-wired client mode.
 */
@Configuration
public class StubGitHubApiClient {

    @Bean
    @ConditionalOnMissingBean(GitHubApiClient.class)
    public GitHubApiClient defaultGitHubApiClient() {
        return new GitHubApiClient() {
            @Override
            public Response get(String path, String token) {
                return new Response(503, Map.of(), Map.of(), null);
            }

            @Override
            public Response post(String path, String token, Map<String, Object> body) {
                return new Response(503, Map.of(), Map.of(), null);
            }
        };
    }
}
