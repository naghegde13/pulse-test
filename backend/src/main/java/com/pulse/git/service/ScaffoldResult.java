package com.pulse.git.service;

import java.util.List;

public record ScaffoldResult(
        String gitRepoId,
        String commitSha,
        String pushStatus,
        List<ScaffoldDomainResult> domains
) {}
