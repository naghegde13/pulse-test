package com.pulse.git.service;

import java.util.List;

public record ScaffoldPreview(
        String tenantId,
        String repoType,
        String branchName,
        GitIdentityReadiness gitIdentity,
        boolean topLevelMissing,
        List<String> topLevelPaths,
        List<ScaffoldDomainPreview> domains
) {}
