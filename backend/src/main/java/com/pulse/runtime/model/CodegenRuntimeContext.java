package com.pulse.runtime.model;

public record CodegenRuntimeContext(
        RuntimePersona activePersona,
        String targetEnvironment,
        String tenantId,
        String legalRuntimeMatrixVersion,
        SecretAuthorityKind secretAuthority
) {}
