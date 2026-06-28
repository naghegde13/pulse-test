package com.pulse.cobol.service;

import com.pulse.cobol.model.CobolDiscoverySession;
import com.pulse.cobol.repository.CobolDiscoveryArtifactRepository;
import com.pulse.cobol.repository.CobolDiscoveryMessageRepository;
import com.pulse.cobol.repository.CobolDiscoveryRunRepository;
import com.pulse.cobol.repository.CobolDiscoverySessionRepository;
import com.pulse.cobol.repository.CobolParsingProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CobolDiscoveryServiceTest {

    @Test
    void updateCopybookTextRejectsInvalidCopybookBeforePersisting() throws Exception {
        CobolDiscoverySessionRepository sessionRepository = mock(CobolDiscoverySessionRepository.class);
        CobolDiscoveryMessageRepository messageRepository = mock(CobolDiscoveryMessageRepository.class);
        CobolDiscoveryArtifactRepository artifactRepository = mock(CobolDiscoveryArtifactRepository.class);
        CobolDiscoveryRunRepository runRepository = mock(CobolDiscoveryRunRepository.class);
        CobolParsingProfileRepository profileRepository = mock(CobolParsingProfileRepository.class);
        CobolDiscoveryStorageService storageService = mock(CobolDiscoveryStorageService.class);
        CobolSparkPreviewService sparkPreviewService = mock(CobolSparkPreviewService.class);
        CobolDockerSparkPreviewService dockerSparkPreviewService = mock(CobolDockerSparkPreviewService.class);
        CobolDiscoveryAssistantService assistantService = mock(CobolDiscoveryAssistantService.class);
        CobolDiscoveryRunStreamService runStreamService = mock(CobolDiscoveryRunStreamService.class);
        CobolDiscoverySessionStreamService sessionStreamService = mock(CobolDiscoverySessionStreamService.class);

        CobolDiscoverySession session = new CobolDiscoverySession();
        session.setId("session-1");
        session.setTenantId("tenant-1");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(runRepository.findByExpiresAtBeforeAndCleanupStatus(any(), anyString())).thenReturn(List.of());

        CobolDiscoveryService service = new CobolDiscoveryService(
                sessionRepository,
                messageRepository,
                artifactRepository,
                runRepository,
                profileRepository,
                storageService,
                new CobolCopybookAnalyzer(),
                sparkPreviewService,
                dockerSparkPreviewService,
                assistantService,
                runStreamService,
                sessionStreamService
        );

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.updateCopybookText(
                        "tenant-1",
                        "session-1",
                        "copybook.cob",
                        """
                        01 ROOT-REC.
                           05 FIELD-A PIC X(4)
                           05 FIELD-B PIC X(2).
                        """
                )
        );

        assertTrue(ex.getMessage().toLowerCase().contains("syntax"));
        verify(storageService, never()).storeTextArtifact(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }
}
