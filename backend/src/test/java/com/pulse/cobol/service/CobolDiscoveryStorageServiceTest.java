package com.pulse.cobol.service;

import com.pulse.cobol.model.CobolDiscoveryArtifact;
import com.pulse.cobol.repository.CobolDiscoveryArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CobolDiscoveryStorageServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private CobolDiscoveryArtifactRepository artifactRepository;

    private CobolDiscoveryStorageService service;

    private final AtomicInteger idCounter = new AtomicInteger(1);

    @BeforeEach
    void setUp() {
        service = new CobolDiscoveryStorageService(artifactRepository, tempDir.toString());
        idCounter.set(1);
    }

    private void stubRepoSave() {
        when(artifactRepository.save(any(CobolDiscoveryArtifact.class)))
                .thenAnswer(invocation -> {
                    CobolDiscoveryArtifact a = invocation.getArgument(0);
                    if (a.getId() == null) {
                        a.setId("test-id-" + idCounter.getAndIncrement());
                    }
                    return a;
                });
    }

    private void stubNoPriorArtifacts() {
        when(artifactRepository.findBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtAsc(
                any(), any(), eq("ACTIVE")))
                .thenReturn(List.of());
    }

    // -----------------------------------------------------------------------
    //  storeArtifact tests
    // -----------------------------------------------------------------------

    @Test
    void storeArtifact_writesFileToDisk() throws IOException {
        stubNoPriorArtifacts();
        stubRepoSave();

        byte[] content = "HELLO WORLD".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.cob", "application/octet-stream", content);

        CobolDiscoveryArtifact artifact = service.storeArtifact(
                "acme", "sess-1", "copybook", file, Duration.ofHours(1));

        Path storedPath = Path.of(artifact.getStorageUri());
        assertTrue(Files.exists(storedPath), "Stored file must exist on disk");
        assertArrayEquals(content, Files.readAllBytes(storedPath));
    }

    @Test
    void storeArtifact_computesSha256Correctly() throws Exception {
        stubNoPriorArtifacts();
        stubRepoSave();

        byte[] content = "deterministic content".getBytes(StandardCharsets.UTF_8);
        String expectedSha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));

        MockMultipartFile file = new MockMultipartFile(
                "file", "hash-check.dat", "application/octet-stream", content);

        CobolDiscoveryArtifact artifact = service.storeArtifact(
                "acme", "sess-2", "data_file", file, Duration.ofHours(1));

        assertEquals(expectedSha, artifact.getSha256());
    }

    @Test
    void storeArtifact_setsSizeBytesCorrectly() throws IOException {
        stubNoPriorArtifacts();
        stubRepoSave();

        byte[] content = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        MockMultipartFile file = new MockMultipartFile(
                "file", "five-bytes.bin", "application/octet-stream", content);

        CobolDiscoveryArtifact artifact = service.storeArtifact(
                "acme", "sess-3", "data_file", file, Duration.ofHours(1));

        assertEquals(5, artifact.getSizeBytes());
    }

    @Test
    void storeArtifact_setsCorrectArtifactType() throws IOException {
        stubNoPriorArtifacts();
        stubRepoSave();

        MockMultipartFile file = new MockMultipartFile(
                "file", "my.cpy", "application/octet-stream", "data".getBytes());

        CobolDiscoveryArtifact artifact = service.storeArtifact(
                "acme", "sess-4", "copybook", file, Duration.ofHours(1));

        assertEquals("copybook", artifact.getArtifactType());
    }

    @Test
    void storeArtifact_sanitizesFilenameSpecialChars() throws IOException {
        stubNoPriorArtifacts();
        stubRepoSave();

        MockMultipartFile file = new MockMultipartFile(
                "file", "my file (copy) [v2].cob", "application/octet-stream",
                "content".getBytes());

        CobolDiscoveryArtifact artifact = service.storeArtifact(
                "acme", "sess-5", "copybook", file, Duration.ofHours(1));

        Path storedPath = Path.of(artifact.getStorageUri());
        assertTrue(Files.exists(storedPath), "File with sanitized name must exist");
        // The stored filename should not contain special characters
        String storedFilename = storedPath.getFileName().toString();
        assertFalse(storedFilename.contains(" "), "Spaces should be sanitized");
        assertFalse(storedFilename.contains("("), "Parens should be sanitized");
        assertFalse(storedFilename.contains("["), "Brackets should be sanitized");
    }

    @Test
    void storeArtifact_purgesPriorActiveArtifactOfSameType() throws IOException {
        // Create a prior artifact with a real file on disk
        Path priorFile = tempDir.resolve("acme").resolve("sess-6").resolve("copybook").resolve("old.cob");
        Files.createDirectories(priorFile.getParent());
        Files.writeString(priorFile, "old content");

        CobolDiscoveryArtifact priorArtifact = new CobolDiscoveryArtifact();
        priorArtifact.setId("prior-id-1");
        priorArtifact.setStorageUri(priorFile.toString());
        priorArtifact.setCleanupStatus("ACTIVE");

        when(artifactRepository.findBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtAsc(
                "sess-6", "copybook", "ACTIVE"))
                .thenReturn(List.of(priorArtifact))
                .thenReturn(List.of()); // second call during nested purge check returns empty
        stubRepoSave();

        MockMultipartFile file = new MockMultipartFile(
                "file", "new.cob", "application/octet-stream", "new content".getBytes());

        service.storeArtifact("acme", "sess-6", "copybook", file, Duration.ofHours(1));

        assertFalse(Files.exists(priorFile), "Prior artifact file should be deleted");
        assertEquals("DELETED", priorArtifact.getCleanupStatus());
        // save called for: prior artifact cleanup + new artifact = at least 2 times
        verify(artifactRepository, atLeast(2)).save(any(CobolDiscoveryArtifact.class));
    }

    @Test
    void storeArtifact_setsCleanupStatusToActive() throws IOException {
        stubNoPriorArtifacts();
        stubRepoSave();

        MockMultipartFile file = new MockMultipartFile(
                "file", "active.cob", "application/octet-stream", "data".getBytes());

        CobolDiscoveryArtifact artifact = service.storeArtifact(
                "acme", "sess-7", "copybook", file, Duration.ofHours(1));

        assertEquals("ACTIVE", artifact.getCleanupStatus());
    }

    // -----------------------------------------------------------------------
    //  storeTextArtifact tests
    // -----------------------------------------------------------------------

    @Test
    void storeTextArtifact_writesUtf8ContentToDisk() throws IOException {
        stubNoPriorArtifacts();
        stubRepoSave();

        String textContent = "SELECT * FROM customers WHERE status = 'ACTIVE';";

        CobolDiscoveryArtifact artifact = service.storeTextArtifact(
                "acme", "sess-8", "generated_sql", "query.sql",
                textContent, Duration.ofHours(1));

        Path storedPath = Path.of(artifact.getStorageUri());
        assertTrue(Files.exists(storedPath));
        String readBack = Files.readString(storedPath, StandardCharsets.UTF_8);
        assertEquals(textContent, readBack);
    }

    @Test
    void storeTextArtifact_setsContentTypeToTextPlain() throws IOException {
        stubNoPriorArtifacts();
        stubRepoSave();

        CobolDiscoveryArtifact artifact = service.storeTextArtifact(
                "acme", "sess-9", "report", "report.txt",
                "Some report content", Duration.ofHours(1));

        assertEquals("text/plain", artifact.getContentType());
    }

    // -----------------------------------------------------------------------
    //  readText / readBytes tests
    // -----------------------------------------------------------------------

    @Test
    void readText_returnsStoredTextContent() throws IOException {
        String expected = "Line 1\nLine 2\nLine 3";
        Path filePath = tempDir.resolve("read-text-test.txt");
        Files.writeString(filePath, expected, StandardCharsets.UTF_8);

        CobolDiscoveryArtifact artifact = new CobolDiscoveryArtifact();
        artifact.setStorageUri(filePath.toString());

        String result = service.readText(artifact);
        assertEquals(expected, result);
    }

    @Test
    void readBytes_returnsStoredBinaryContent() throws IOException {
        byte[] expected = new byte[]{0x00, 0x0A, 0x1B, (byte) 0xFF, 0x42};
        Path filePath = tempDir.resolve("read-bytes-test.bin");
        Files.write(filePath, expected);

        CobolDiscoveryArtifact artifact = new CobolDiscoveryArtifact();
        artifact.setStorageUri(filePath.toString());

        byte[] result = service.readBytes(artifact);
        assertArrayEquals(expected, result);
    }

    // -----------------------------------------------------------------------
    //  resolve test
    // -----------------------------------------------------------------------

    @Test
    void resolve_returnsPathFromStorageUri() {
        String uri = "/some/path/to/artifact.dat";
        CobolDiscoveryArtifact artifact = new CobolDiscoveryArtifact();
        artifact.setStorageUri(uri);

        Path resolved = service.resolve(artifact);
        assertEquals(Path.of(uri), resolved);
    }

    // -----------------------------------------------------------------------
    //  cleanupArtifact tests
    // -----------------------------------------------------------------------

    @Test
    void cleanupArtifact_deletesFileAndMarksStatusDeleted() throws IOException {
        stubRepoSave();

        Path filePath = tempDir.resolve("to-be-cleaned.dat");
        Files.writeString(filePath, "ephemeral content");

        CobolDiscoveryArtifact artifact = new CobolDiscoveryArtifact();
        artifact.setId("cleanup-1");
        artifact.setStorageUri(filePath.toString());
        artifact.setCleanupStatus("ACTIVE");

        service.cleanupArtifact(artifact);

        assertFalse(Files.exists(filePath), "File should be deleted after cleanup");
        assertEquals("DELETED", artifact.getCleanupStatus());
        verify(artifactRepository).save(artifact);
    }

    @Test
    void cleanupArtifact_handlesAlreadyDeletedFile() {
        stubRepoSave();

        CobolDiscoveryArtifact artifact = new CobolDiscoveryArtifact();
        artifact.setId("cleanup-2");
        artifact.setStorageUri(tempDir.resolve("nonexistent-file.dat").toString());
        artifact.setCleanupStatus("ACTIVE");

        // Should not throw
        assertDoesNotThrow(() -> service.cleanupArtifact(artifact));
        assertEquals("DELETED", artifact.getCleanupStatus());
        verify(artifactRepository).save(artifact);
    }

    // -----------------------------------------------------------------------
    //  purgeExpiredArtifacts tests
    // -----------------------------------------------------------------------

    @Test
    void purgeExpiredArtifacts_cleansUpAllExpiredActive() throws IOException {
        stubRepoSave();

        // Create two expired artifacts with real files
        Path file1 = tempDir.resolve("expired-1.dat");
        Path file2 = tempDir.resolve("expired-2.dat");
        Files.writeString(file1, "expired content 1");
        Files.writeString(file2, "expired content 2");

        CobolDiscoveryArtifact a1 = new CobolDiscoveryArtifact();
        a1.setId("exp-1");
        a1.setStorageUri(file1.toString());
        a1.setCleanupStatus("ACTIVE");

        CobolDiscoveryArtifact a2 = new CobolDiscoveryArtifact();
        a2.setId("exp-2");
        a2.setStorageUri(file2.toString());
        a2.setCleanupStatus("ACTIVE");

        when(artifactRepository.findByExpiresAtBeforeAndCleanupStatus(any(Instant.class), eq("ACTIVE")))
                .thenReturn(List.of(a1, a2));

        service.purgeExpiredArtifacts();

        assertFalse(Files.exists(file1), "Expired file 1 should be deleted");
        assertFalse(Files.exists(file2), "Expired file 2 should be deleted");
        assertEquals("DELETED", a1.getCleanupStatus());
        assertEquals("DELETED", a2.getCleanupStatus());
        verify(artifactRepository, times(2)).save(any(CobolDiscoveryArtifact.class));
    }

    @Test
    void purgeExpiredArtifacts_doesNothingWhenNoneExpired() {
        when(artifactRepository.findByExpiresAtBeforeAndCleanupStatus(any(Instant.class), eq("ACTIVE")))
                .thenReturn(List.of());

        service.purgeExpiredArtifacts();

        verify(artifactRepository, never()).save(any(CobolDiscoveryArtifact.class));
    }
}
