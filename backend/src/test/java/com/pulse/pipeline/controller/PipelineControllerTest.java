package com.pulse.pipeline.controller;

import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.service.PipelineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineControllerTest {

    @Mock private PipelineService pipelineService;
    @Mock private com.pulse.pipeline.service.StoryGenerationService storyGenerationService;

    @InjectMocks
    private PipelineController controller;

    @Test
    void updateOrchestration_returnsUpdatedVersion() {
        PipelineVersion version = new PipelineVersion();
        version.setId("version-1");
        version.setScheduleCron("0 6 * * *");

        var request = new PipelineController.UpdateOrchestrationRequest(
                "0 6 * * *",
                true,
                2,
                false,
                java.util.Map.of("ScheduleAndTriggers", java.util.Map.of("schedule_type", "cron"))
        );

        when(pipelineService.updateOrchestration("tenant-1", "pipeline-1", "version-1", request))
                .thenReturn(version);

        ResponseEntity<PipelineVersion> response = controller.updateOrchestration(
                "tenant-1", "pipeline-1", "version-1", request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("version-1", response.getBody().getId());
    }
}
