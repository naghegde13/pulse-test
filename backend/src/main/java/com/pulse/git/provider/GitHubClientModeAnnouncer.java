package com.pulse.git.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

/**
 * PKT-FINAL-4 (BUG-35): single source of truth for the boot-time
 * announcement of the active GitHub client mode.
 *
 * <p>The prior implementation logged from {@code @PostConstruct} hooks on
 * both {@link StubGitHubApiClient} and {@link DefaultGitHubApiClient}.
 * Because {@link StubGitHubApiClient} is an unconditional
 * {@code @Configuration}, its hook fired even when the real client was
 * wired, producing a misleading STUB WARN alongside the REAL INFO. This
 * announcer reads {@code pulse.git.github.enabled} once after context
 * refresh and logs the active mode exactly once.
 *
 * <p>The {@code announced} flag suppresses duplicate emission when the
 * Spring DevTools restart fires multiple {@link ContextRefreshedEvent}s
 * for the same JVM lifecycle.
 */
@Component
public class GitHubClientModeAnnouncer implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger log = LoggerFactory.getLogger(GitHubClientModeAnnouncer.class);

    private final boolean githubEnabled;
    private boolean announced = false;

    public GitHubClientModeAnnouncer(@Value("${pulse.git.github.enabled:false}") boolean githubEnabled) {
        this.githubEnabled = githubEnabled;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (announced) {
            return;
        }
        announced = true;
        announce();
    }

    /** Visible for tests so the announcement logic can be exercised without firing real events. */
    void announce() {
        if (githubEnabled) {
            log.info("GitHub client mode: REAL (pulse.git.github.enabled=true). "
                    + "PAT validation issues outbound HTTP calls to https://api.github.com.");
        } else {
            log.warn("GitHub client mode: STUB (pulse.git.github.enabled=false). "
                    + "PAT validation will return PROVIDER_UNAVAILABLE; no HTTP calls are made to api.github.com. "
                    + "Set pulse.git.github.enabled=true (or PULSE_GIT_GITHUB_ENABLED=true) to use the real client.");
        }
    }

    /** Visible for tests. */
    boolean isAnnounced() {
        return announced;
    }
}
