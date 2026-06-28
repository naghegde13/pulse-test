package com.pulse.cobol.service;

import com.pulse.cobol.model.CobolDiscoveryArtifact;
import com.pulse.cobol.repository.CobolDiscoveryArtifactRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class CobolDiscoveryStorageService {

    private final CobolDiscoveryArtifactRepository artifactRepository;
    private final Path root;

    public CobolDiscoveryStorageService(
            CobolDiscoveryArtifactRepository artifactRepository,
            @Value("${pulse.cobol.discovery.storage-root:}") String configuredRoot) {
        this.artifactRepository = artifactRepository;
        this.root = (configuredRoot == null || configuredRoot.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"), "pulse-ebcdic-discovery")
                : Path.of(configuredRoot);
    }

    public CobolDiscoveryArtifact storeArtifact(
            String tenantId,
            String sessionId,
            String artifactType,
            MultipartFile file,
            Duration ttl) throws IOException {
        purgeActiveArtifactsOfSameType(sessionId, artifactType);
        Files.createDirectories(root);
        String originalFilename = file.getOriginalFilename() == null ? artifactType : file.getOriginalFilename();
        Path dir = root.resolve(tenantId).resolve(sessionId).resolve(artifactType);
        Files.createDirectories(dir);

        String safeName = originalFilename.replaceAll("[^A-Za-z0-9._-]", "_");
        Path target = dir.resolve(System.currentTimeMillis() + "-" + safeName);
        byte[] bytes = file.getBytes();
        Files.write(target, bytes);

        CobolDiscoveryArtifact artifact = new CobolDiscoveryArtifact();
        artifact.setTenantId(tenantId);
        artifact.setSessionId(sessionId);
        artifact.setArtifactType(artifactType);
        artifact.setOriginalFilename(originalFilename);
        artifact.setStorageUri(target.toString());
        artifact.setSha256(sha256(bytes));
        artifact.setSizeBytes(bytes.length);
        artifact.setContentType(file.getContentType());
        artifact.setCleanupStatus("ACTIVE");
        artifact.setExpiresAt(Instant.now().plus(ttl));
        return artifactRepository.save(artifact);
    }

    public CobolDiscoveryArtifact storeTextArtifact(
            String tenantId,
            String sessionId,
            String artifactType,
            String filename,
            String content,
            Duration ttl) throws IOException {
        purgeActiveArtifactsOfSameType(sessionId, artifactType);
        Files.createDirectories(root);
        Path dir = root.resolve(tenantId).resolve(sessionId).resolve(artifactType);
        Files.createDirectories(dir);

        String safeName = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        Path target = dir.resolve(System.currentTimeMillis() + "-" + safeName);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(target, bytes);

        CobolDiscoveryArtifact artifact = new CobolDiscoveryArtifact();
        artifact.setTenantId(tenantId);
        artifact.setSessionId(sessionId);
        artifact.setArtifactType(artifactType);
        artifact.setOriginalFilename(filename);
        artifact.setStorageUri(target.toString());
        artifact.setSha256(sha256(bytes));
        artifact.setSizeBytes(bytes.length);
        artifact.setContentType("text/plain");
        artifact.setCleanupStatus("ACTIVE");
        artifact.setExpiresAt(Instant.now().plus(ttl));
        return artifactRepository.save(artifact);
    }

    public Path resolve(CobolDiscoveryArtifact artifact) {
        return Path.of(artifact.getStorageUri());
    }

    public String readText(CobolDiscoveryArtifact artifact) throws IOException {
        return Files.readString(resolve(artifact), StandardCharsets.UTF_8);
    }

    public byte[] readBytes(CobolDiscoveryArtifact artifact) throws IOException {
        return Files.readAllBytes(resolve(artifact));
    }

    public void cleanupArtifact(CobolDiscoveryArtifact artifact) {
        try {
            Files.deleteIfExists(resolve(artifact));
        } catch (IOException ignored) {
        }
        artifact.setCleanupStatus("DELETED");
        artifactRepository.save(artifact);
    }

    public void purgeExpiredArtifacts() {
        List<CobolDiscoveryArtifact> expired = artifactRepository.findByExpiresAtBeforeAndCleanupStatus(
                Instant.now(), "ACTIVE");
        for (CobolDiscoveryArtifact artifact : expired) {
            cleanupArtifact(artifact);
        }
    }

    public InputStream open(CobolDiscoveryArtifact artifact) throws IOException {
        return Files.newInputStream(resolve(artifact));
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute SHA-256", e);
        }
    }

    private void purgeActiveArtifactsOfSameType(String sessionId, String artifactType) {
        List<CobolDiscoveryArtifact> active = artifactRepository
                .findBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtAsc(sessionId, artifactType, "ACTIVE");
        for (CobolDiscoveryArtifact artifact : active) {
            cleanupArtifact(artifact);
        }
    }
}
