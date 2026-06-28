package com.pulse.runtime;

import com.pulse.runtime.config.RuntimeAuthorityProperties;
import com.pulse.runtime.service.RuntimeAuthorityService;

public final class TestRuntimeAuthorityFactory {

    private TestRuntimeAuthorityFactory() {}

    public static RuntimeAuthorityService gcpPulse() {
        RuntimeAuthorityProperties props = new RuntimeAuthorityProperties();
        props.setActivePersona("GCP_PULSE");
        RuntimeAuthorityService service = new RuntimeAuthorityService(props);
        service.initialize();
        return service;
    }

    public static RuntimeAuthorityService dpcPulse() {
        RuntimeAuthorityProperties props = new RuntimeAuthorityProperties();
        props.setActivePersona("DPC_PULSE");
        RuntimeAuthorityService service = new RuntimeAuthorityService(props);
        service.initialize();
        return service;
    }
}
