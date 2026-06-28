package com.pulse.cobol.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CobolDiscoveryCleanupScheduler {

    private final CobolDiscoveryService discoveryService;

    public CobolDiscoveryCleanupScheduler(CobolDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @Scheduled(fixedDelayString = "${pulse.cobol.discovery.cleanup-delay-ms:600000}")
    public void purgeExpiredState() {
        discoveryService.purgeExpiredState();
    }
}
