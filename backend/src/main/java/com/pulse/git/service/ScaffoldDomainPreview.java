package com.pulse.git.service;

import java.util.List;

public record ScaffoldDomainPreview(
        String domainId,
        String domainName,
        String domainSlug,
        String status,
        List<String> paths
) {}
