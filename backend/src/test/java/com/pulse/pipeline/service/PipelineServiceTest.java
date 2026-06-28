package com.pulse.pipeline.service;

import com.pulse.auth.service.TenantService;
import com.pulse.command.model.CommandLog;
import com.pulse.command.model.CommandStatus;
import com.pulse.command.service.CommandService;
import com.pulse.command.service.PlanService;
import com.pulse.command.service.PlanService.PlannedCommand;
import com.pulse.command.model.Plan;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.pipeline.controller.PipelineController.CreatePipelineRequest;
import com.pulse.pipeline.controller.PipelineController.CreateRevisionRequest;
import com.pulse.pipeline.controller.PipelineController.UpdateOrchestrationRequest;
import com.pulse.pipeline.controller.PipelineController.UpdatePipelineRequest;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineStage;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.SchemaConflictRepository;
import com.pulse.runtime.model.RuntimeAuthority;
import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.model.SecretAuthorityKind;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.DomainRepository;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipelineServiceTest {

    @Mock private PipelineRepository pipelineRepository;
    @Mock private PipelineVersionRepository versionRepository;
    @Mock private DomainRepository domainRepository;
    @Mock private TenantService tenantService;
    @Mock private CommandService commandService;
    @Mock private PlanService planService;
    @Mock private SchemaConflictRepository schemaConflictRepository;
    @Mock private RuntimeAuthorityService runtimeAuthorityService;

    @InjectMocks
    private PipelineService service;

    private Pipeline testPipeline;
    private PipelineVersion testVersion;

    @BeforeEach
    void setUp() {
        testPipeline = new Pipeline();
        testPipeline.setId("pipeline-1");
        testPipeline.setTenantId("tenant-1");
        testPipeline.setName("Test Pipeline");
        testPipeline.setDescription("A test pipeline");
        testPipeline.setDomainName("Servicing");
        testPipeline.setDomainId("domain-1");
        testPipeline.setCreatedBy("user-1");
        testPipeline.setActiveVersionId("version-1");

        testVersion = new PipelineVersion();
        testVersion.setId("version-1");
        testVersion.setPipelineId("pipeline-1");
        testVersion.setRevision(1);
        testVersion.setLifecycleStage(PipelineStage.ENGINEERING);
        testVersion.setCreatedBy("user-1");
    }

    // -----------------------------------------------------------------------
    //  create tests
    // -----------------------------------------------------------------------

    @Test
    void create_createsPipelineWithInitialVersion() {
        // Given
        CreatePipelineRequest request = new CreatePipelineRequest(
                "New Pipeline", "A new pipeline", "Servicing", null, "DPC");
        Domain domain = new Domain();
        domain.setId("domain-1");
        domain.setTenantId("tenant-1");
        domain.setName("Servicing");
        when(domainRepository.findByTenantIdAndName("tenant-1", "Servicing"))
                .thenReturn(Optional.of(domain));

        Plan plan = new Plan();
        plan.setId("plan-1");
        when(planService.createPreview(eq("tenant-1"), isNull(), anyString(), anyString(), anyList()))
                .thenReturn(plan);
        when(planService.apply(eq("plan-1"), eq("tenant-1"), anyString(), anyList()))
                .thenReturn(plan);
        when(pipelineRepository.findByTenantIdOrderByUpdatedAtDesc("tenant-1"))
                .thenReturn(List.of(testPipeline));

        // When
        Pipeline result = service.create("tenant-1", request);

        // Then
        assertNotNull(result);
        assertEquals("Test Pipeline", result.getName());
        verify(planService).createPreview(eq("tenant-1"), isNull(), anyString(), anyString(), anyList());
        verify(planService).apply(eq("plan-1"), eq("tenant-1"), anyString(), anyList());
    }

    @Test
    void create_invalidDomain_throwsException() {
        // Given
        CreatePipelineRequest request = new CreatePipelineRequest(
                "Bad Pipeline", "desc", "NonexistentDomain", null, "DPC");
        when(domainRepository.findByTenantIdAndName("tenant-1", "NonexistentDomain"))
                .thenReturn(Optional.empty());

        // When/Then
        assertThrows(IllegalArgumentException.class,
                () -> service.create("tenant-1", request));
    }

    @Test
    void create_withDomainId_resolvesCanonicalDomain() {
        CreatePipelineRequest request = new CreatePipelineRequest(
                "Canonical Pipeline", "desc", null, "domain-1", "DPC");
        Domain domain = new Domain();
        domain.setId("domain-1");
        domain.setTenantId("tenant-1");
        domain.setName("Servicing");
        when(domainRepository.findById("domain-1"))
                .thenReturn(Optional.of(domain));

        Plan plan = new Plan();
        plan.setId("plan-1");
        when(planService.createPreview(eq("tenant-1"), isNull(), anyString(), anyString(), anyList()))
                .thenReturn(plan);
        when(planService.apply(eq("plan-1"), eq("tenant-1"), anyString(), anyList()))
                .thenReturn(plan);
        when(pipelineRepository.findByTenantIdOrderByUpdatedAtDesc("tenant-1"))
                .thenReturn(List.of(testPipeline));

        Pipeline result = service.create("tenant-1", request);

        assertNotNull(result);
        verify(domainRepository).findById("domain-1");
    }

    @Test
    void create_withoutDefaultStorageBackend_derivesFromRuntimeAuthority() {
        CreatePipelineRequest request = new CreatePipelineRequest(
                "Runtime Pipeline", "desc", null, "domain-1", null);
        Domain domain = new Domain();
        domain.setId("domain-1");
        domain.setTenantId("tenant-1");
        domain.setName("Servicing");
        when(domainRepository.findById("domain-1"))
                .thenReturn(Optional.of(domain));
        when(runtimeAuthorityService.getAuthority()).thenReturn(gcpRuntimeAuthority());

        Plan plan = new Plan();
        plan.setId("plan-1");
        when(planService.createPreview(eq("tenant-1"), isNull(), anyString(), anyString(), anyList()))
                .thenReturn(plan);
        when(planService.apply(eq("plan-1"), eq("tenant-1"), anyString(), anyList()))
                .thenReturn(plan);
        when(pipelineRepository.findByTenantIdOrderByUpdatedAtDesc("tenant-1"))
                .thenReturn(List.of(testPipeline));

        Pipeline result = service.create("tenant-1", request);

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PlannedCommand>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(planService).createPreview(eq("tenant-1"), isNull(), anyString(), anyString(), commandsCaptor.capture());
        assertEquals("GCP", commandsCaptor.getValue().get(0).payload().get("defaultStorageBackend"));
    }

    private RuntimeAuthority gcpRuntimeAuthority() {
        return new RuntimeAuthority(
                RuntimePersona.GCP_PULSE,
                RuntimePersona.GCP_PULSE.displayName(),
                Set.of("GCP_COMPOSER_DATAPROC"),
                Set.of("GCP"),
                Set.of("COMPOSER"),
                Set.of("DATAPROC"),
                Set.of("GCS"),
                Set.of("BIGQUERY"),
                Set.of(RuntimePersona.DPC_PULSE),
                Map.of("bronze", List.of("iceberg_bq_managed")),
                SecretAuthorityKind.GCP_SECRET_MANAGER,
                "test"
        );
    }

    // -----------------------------------------------------------------------
    //  get tests
    // -----------------------------------------------------------------------

    @Test
    void get_returnsPipelineById() {
        // Given
        when(pipelineRepository.findById("pipeline-1")).thenReturn(Optional.of(testPipeline));

        // When
        Pipeline result = service.get("tenant-1", "pipeline-1");

        // Then
        assertEquals("pipeline-1", result.getId());
        assertEquals("Test Pipeline", result.getName());
    }

    @Test
    void get_wrongTenant_throwsException() {
        // Given
        when(pipelineRepository.findById("pipeline-1")).thenReturn(Optional.of(testPipeline));

        // When/Then
        assertThrows(ResourceNotFoundException.class,
                () -> service.get("other-tenant", "pipeline-1"));
    }

    @Test
    void get_notFound_throwsException() {
        // Given
        when(pipelineRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // When/Then
        assertThrows(ResourceNotFoundException.class,
                () -> service.get("tenant-1", "nonexistent"));
    }

    // -----------------------------------------------------------------------
    //  list tests
    // -----------------------------------------------------------------------

    @Test
    void list_returnsPipelinesForTenant() {
        // Given
        when(pipelineRepository.findByTenantIdOrderByUpdatedAtDesc("tenant-1"))
                .thenReturn(List.of(testPipeline));

        // When
        List<Pipeline> result = service.listByTenant("tenant-1");

        // Then
        assertEquals(1, result.size());
        assertEquals("Test Pipeline", result.get(0).getName());
        verify(tenantService).getTenant("tenant-1");
    }

    // -----------------------------------------------------------------------
    //  update tests
    // -----------------------------------------------------------------------

    @Test
    void update_updatesNameAndDescription() {
        // Given
        UpdatePipelineRequest request = new UpdatePipelineRequest("Updated Name", "Updated Desc", null);

        when(pipelineRepository.findById("pipeline-1")).thenReturn(Optional.of(testPipeline));
        CommandLog cmd = buildSucceededCommand();
        when(commandService.execute(eq(PipelineCommandHandlers.UPDATE_PIPELINE),
                anyString(), eq("pipeline-1"), eq("tenant-1"), anyString(), isNull(), anyMap()))
                .thenReturn(cmd);

        // When
        Pipeline result = service.update("tenant-1", "pipeline-1", request);

        // Then
        assertNotNull(result);
        verify(commandService).execute(eq(PipelineCommandHandlers.UPDATE_PIPELINE),
                anyString(), eq("pipeline-1"), eq("tenant-1"), anyString(), isNull(), anyMap());
    }

    // -----------------------------------------------------------------------
    //  delete tests
    // -----------------------------------------------------------------------

    @Test
    void delete_deletesPipeline() {
        // Given
        when(pipelineRepository.findById("pipeline-1")).thenReturn(Optional.of(testPipeline));
        CommandLog cmd = buildSucceededCommand();
        when(commandService.execute(eq(PipelineCommandHandlers.DELETE_PIPELINE),
                anyString(), eq("pipeline-1"), eq("tenant-1"), anyString(), isNull(), anyMap()))
                .thenReturn(cmd);

        // When
        service.delete("tenant-1", "pipeline-1");

        // Then
        verify(commandService).execute(eq(PipelineCommandHandlers.DELETE_PIPELINE),
                anyString(), eq("pipeline-1"), eq("tenant-1"), anyString(), isNull(), anyMap());
    }

    @Test
    void delete_commandFails_throwsException() {
        // Given
        when(pipelineRepository.findById("pipeline-1")).thenReturn(Optional.of(testPipeline));
        CommandLog cmd = buildFailedCommand("Cannot delete: versions deployed");
        when(commandService.execute(eq(PipelineCommandHandlers.DELETE_PIPELINE),
                anyString(), eq("pipeline-1"), eq("tenant-1"), anyString(), isNull(), anyMap()))
                .thenReturn(cmd);

        // When/Then
        assertThrows(IllegalArgumentException.class,
                () -> service.delete("tenant-1", "pipeline-1"));
    }

    // -----------------------------------------------------------------------
    //  transitionStage tests
    // -----------------------------------------------------------------------

    @Test
    void transitionStage_movesPipelineThroughLifecycleStages() {
        // Given
        when(pipelineRepository.findById("pipeline-1")).thenReturn(Optional.of(testPipeline));
        when(versionRepository.findById("version-1")).thenReturn(Optional.of(testVersion));
        CommandLog cmd = buildSucceededCommand();
        when(commandService.execute(eq(PipelineCommandHandlers.TRANSITION_STAGE),
                anyString(), eq("version-1"), eq("tenant-1"), anyString(), isNull(), anyMap()))
                .thenReturn(cmd);
        when(schemaConflictRepository.findByVersionIdAndResolutionStatusOrderByCreatedAtDesc("version-1", "open"))
                .thenReturn(List.of());

        // When
        PipelineVersion result = service.transitionStage(
                "tenant-1", "pipeline-1", "version-1", PipelineStage.DEV_DEPLOYED);

        // Then
        assertNotNull(result);
        verify(commandService).execute(eq(PipelineCommandHandlers.TRANSITION_STAGE),
                anyString(), eq("version-1"), eq("tenant-1"), anyString(), isNull(), anyMap());
    }

    @Test
    void transitionStage_versionNotBelongingToPipeline_throwsException() {
        // Given
        PipelineVersion wrongVersion = new PipelineVersion();
        wrongVersion.setId("version-2");
        wrongVersion.setPipelineId("other-pipeline");
        wrongVersion.setRevision(1);
        wrongVersion.setLifecycleStage(PipelineStage.ENGINEERING);
        wrongVersion.setCreatedBy("user-1");

        when(pipelineRepository.findById("pipeline-1")).thenReturn(Optional.of(testPipeline));
        when(versionRepository.findById("version-2")).thenReturn(Optional.of(wrongVersion));

        // When/Then
        assertThrows(IllegalArgumentException.class,
                () -> service.transitionStage("tenant-1", "pipeline-1", "version-2", PipelineStage.DEV_DEPLOYED));
    }

    @Test
    void updateOrchestration_updatesScheduleFieldsAndMetadata() {
        when(pipelineRepository.findById("pipeline-1")).thenReturn(Optional.of(testPipeline));
        when(versionRepository.findById("version-1")).thenReturn(Optional.of(testVersion));
        when(versionRepository.save(any(PipelineVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateOrchestrationRequest request = new UpdateOrchestrationRequest(
                "0 6 * * *", true, 2, true,
                Map.of("ScheduleAndTriggers", Map.of("schedule_type", "cron"))
        );

        PipelineVersion result = service.updateOrchestration("tenant-1", "pipeline-1", "version-1", request);

        assertEquals("0 6 * * *", result.getScheduleCron());
        assertTrue(result.getCatchupEnabled());
        assertEquals(2, result.getMaxActiveRuns());
        assertTrue(result.getDependsOnPast());
        assertNotNull(result.getMetadata());
        assertEquals(
                Map.of(
                        "scheduleType", "cron",
                        "scheduleCron", "0 6 * * *",
                        "timezone", "UTC",
                        "retryCount", 3,
                        "catchupEnabled", true,
                        "maxActiveRuns", 2,
                        "dependsOnPast", true
                ),
                result.getMetadata().get("orchestrationPolicy")
        );
        assertTrue(result.getMetadata().containsKey("orchestrationPolicyBlueprints"));
    }

    @Test
    void updateOrchestration_defaultsNullPolicyFieldsAndPreservesExistingMetadata() {
        when(pipelineRepository.findById("pipeline-1")).thenReturn(Optional.of(testPipeline));
        testVersion.setMetadata(new java.util.LinkedHashMap<>(Map.of("existingKey", "existing-value")));
        when(versionRepository.findById("version-1")).thenReturn(Optional.of(testVersion));
        when(versionRepository.save(any(PipelineVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateOrchestrationRequest request = new UpdateOrchestrationRequest(
                null, null, null, null, null
        );

        PipelineVersion result = service.updateOrchestration("tenant-1", "pipeline-1", "version-1", request);

        assertNull(result.getScheduleCron());
        assertNull(result.getCatchupEnabled());
        assertNull(result.getMaxActiveRuns());
        assertNull(result.getDependsOnPast());
        assertEquals("existing-value", result.getMetadata().get("existingKey"));
        @SuppressWarnings("unchecked")
        Map<String, Object> orchestrationPolicy =
                (Map<String, Object>) result.getMetadata().get("orchestrationPolicy");
        assertEquals("manual", orchestrationPolicy.get("scheduleType"));
        assertNull(orchestrationPolicy.get("scheduleCron"));
        assertEquals("UTC", orchestrationPolicy.get("timezone"));
        assertEquals(3, orchestrationPolicy.get("retryCount"));
        assertEquals(false, orchestrationPolicy.get("catchupEnabled"));
        assertEquals(1, orchestrationPolicy.get("maxActiveRuns"));
        assertEquals(false, orchestrationPolicy.get("dependsOnPast"));
        assertEquals(Map.of(), result.getMetadata().get("orchestrationPolicyBlueprints"));
    }

    @Test
    void updateOrchestration_persistsEventTriggerMetadataWithoutFakeCron() {
        when(pipelineRepository.findById("pipeline-1")).thenReturn(Optional.of(testPipeline));
        when(versionRepository.findById("version-1")).thenReturn(Optional.of(testVersion));
        when(versionRepository.save(any(PipelineVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateOrchestrationRequest request = new UpdateOrchestrationRequest(
                null,
                false,
                1,
                false,
                Map.of(
                        "ScheduleAndTriggers",
                        Map.of(
                                "schedule_type", "event",
                                "trigger_dataset", "orders.ready",
                                "timezone", "America/New_York",
                                "retry_count", 5
                        )
                )
        );

        PipelineVersion result = service.updateOrchestration("tenant-1", "pipeline-1", "version-1", request);

        assertNull(result.getScheduleCron());
        @SuppressWarnings("unchecked")
        Map<String, Object> orchestrationPolicy =
                (Map<String, Object>) result.getMetadata().get("orchestrationPolicy");
        assertEquals("event", orchestrationPolicy.get("scheduleType"));
        assertNull(orchestrationPolicy.get("scheduleCron"));
        assertEquals("orders.ready", orchestrationPolicy.get("triggerDataset"));
        assertEquals("America/New_York", orchestrationPolicy.get("timezone"));
        assertEquals(5, orchestrationPolicy.get("retryCount"));
        assertEquals(false, orchestrationPolicy.get("catchupEnabled"));
        assertEquals(1, orchestrationPolicy.get("maxActiveRuns"));
        assertEquals(false, orchestrationPolicy.get("dependsOnPast"));
    }

    // -----------------------------------------------------------------------
    //  createRevision tests
    // -----------------------------------------------------------------------

    @Test
    void createRevision_createsNewVersionWithIncrementedRevision() {
        // Given
        CreateRevisionRequest request = new CreateRevisionRequest("Bug fix release");

        when(pipelineRepository.findById("pipeline-1")).thenReturn(Optional.of(testPipeline));
        CommandLog cmd = buildSucceededCommand();
        when(commandService.execute(eq(PipelineCommandHandlers.CREATE_REVISION),
                anyString(), eq("pipeline-1"), eq("tenant-1"), anyString(), isNull(), anyMap()))
                .thenReturn(cmd);

        PipelineVersion newVersion = new PipelineVersion();
        newVersion.setId("version-2");
        newVersion.setPipelineId("pipeline-1");
        newVersion.setRevision(2);
        newVersion.setLifecycleStage(PipelineStage.ENGINEERING);
        newVersion.setCreatedBy("user-1");
        newVersion.setChangeSummary("Bug fix release");

        when(versionRepository.findByPipelineIdOrderByCreatedAtDesc("pipeline-1"))
                .thenReturn(List.of(newVersion, testVersion));

        // When
        PipelineVersion result = service.createNewRevision("tenant-1", "pipeline-1", request);

        // Then
        assertNotNull(result);
        assertEquals("version-2", result.getId());
        assertEquals(2, result.getRevision());
        verify(commandService).execute(eq(PipelineCommandHandlers.CREATE_REVISION),
                anyString(), eq("pipeline-1"), eq("tenant-1"), anyString(), isNull(), anyMap());
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private CommandLog buildSucceededCommand() {
        CommandLog cmd = new CommandLog();
        cmd.setId("cmd-1");
        cmd.setStatus(CommandStatus.SUCCEEDED);
        return cmd;
    }

    private CommandLog buildFailedCommand(String errorMessage) {
        CommandLog cmd = new CommandLog();
        cmd.setId("cmd-1");
        cmd.setStatus(CommandStatus.FAILED);
        cmd.setErrorMessage(errorMessage);
        return cmd;
    }
}
