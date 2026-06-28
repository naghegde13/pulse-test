package com.pulse.secret.service;

public record SecretNamingContext(
        String environment,
        String tenantSlug,
        String domainSlug,
        String resourceKind,
        String resourceSlug,
        String fieldSlug,
        String resourceId
) {}
