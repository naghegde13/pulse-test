package com.pulse.broker;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeStandaloneStaticSearchTest {

    @Test
    void runtimeImplementationContainsNoPulseMediatedBrokerOrPeerPulseTokens() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        List<String> forbidden = List.of(
                "peer/v1",
                "peer_pulse_url",
                "peerPulseBaseUrl",
                "/api/v1/internal/broker",
                "PULSE_API_URL",
                "PULSE_PG_",
                "/api/v1/advance",
                "pulse_advance_time_dimension",
                "PULSE_BROKER_API_URL",
                "PULSE_BROKER_INTERNAL_TOKEN");

        List<Path> scannedRoots = List.of(
                root.resolve("src/main/java/com/pulse/broker"),
                root.resolve("src/main/java/com/pulse/codegen/service"),
                root.resolve("src/main/resources/runtime-helpers"),
                root.resolve("src/main/resources/db/migration"));

        StringBuilder failures = new StringBuilder();
        for (Path scanRoot : scannedRoots) {
            if (!Files.exists(scanRoot)) continue;
            try (var files = Files.walk(scanRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> !path.toString().contains("/docs/"))
                        .forEach(path -> checkFile(path, forbidden, failures));
            }
        }
        assertTrue(failures.isEmpty(), failures::toString);
    }

    private static void checkFile(Path path, List<String> forbidden, StringBuilder failures) {
        try {
            String content = Files.readString(path);
            for (String token : forbidden) {
                if (content.contains(token)) {
                    failures.append(path).append(" contains ").append(token).append('\n');
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to scan " + path, e);
        }
    }
}
