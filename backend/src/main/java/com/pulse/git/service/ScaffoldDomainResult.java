package com.pulse.git.service;

public record ScaffoldDomainResult(
        String domainId,
        String domainSlug,
        String status,
        String lastError
) {}
