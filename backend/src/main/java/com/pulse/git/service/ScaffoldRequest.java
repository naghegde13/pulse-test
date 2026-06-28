package com.pulse.git.service;

import java.util.List;

public record ScaffoldRequest(
        Boolean refreshTopLevel,
        List<String> domainIds,
        String mode
) {
    public boolean shouldRefreshTopLevel() {
        return refreshTopLevel == null || refreshTopLevel;
    }

    public ScaffoldMode resolvedMode() {
        return ScaffoldMode.from(mode);
    }

    public static ScaffoldRequest all() {
        return new ScaffoldRequest(true, List.of(), ScaffoldMode.ALL.name());
    }
}
