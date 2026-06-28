package com.pulse.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

public record LoanMasterFixture(Path path, String sha256, int rowCount, int columnCount) {

    public static LoanMasterFixture loadCanonical() throws IOException {
        Path path = resolveCanonicalPath();
        List<String> lines = Files.readAllLines(path);
        if (lines.isEmpty()) {
            throw new IllegalStateException("loan_master.csv is empty: " + path);
        }
        int rowCount = Math.max(0, lines.size() - 1);
        int columnCount = lines.get(0).split(",", -1).length;
        return new LoanMasterFixture(path, sha256(path), rowCount, columnCount);
    }

    private static Path resolveCanonicalPath() {
        List<Path> candidates = List.of(
                Path.of("../data/loan_master.csv"),
                Path.of("data/loan_master.csv"),
                Path.of("/Users/aameradam/projects/dev/PULSE/data/loan_master.csv")
        );
        return candidates.stream()
                .map(Path::toAbsolutePath)
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Could not locate canonical loan_master.csv fixture"));
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.join("\n", Files.readAllLines(path, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IOException("Failed to hash loan_master fixture", e);
        }
    }
}
