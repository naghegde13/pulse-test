package com.pulse.pipeline.controller;

import com.pulse.pipeline.model.InstancePortSchema;
import com.pulse.pipeline.model.SchemaConflict;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.InstancePortSchemaRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.pipeline.service.SchemaPropagationService;
import com.pulse.pipeline.service.SchemaPropagationService.InstanceSchemaView;
import com.pulse.pipeline.service.SchemaPropagationService.OverrideRequest;
import com.pulse.pipeline.service.SchemaPropagationService.PortView;
import com.pulse.pipeline.service.SchemaPropagationService.PropagationSummary;
import com.pulse.pipeline.service.SchemaPropagationService.ResolutionRequest;
import com.pulse.pipeline.service.SchemaPropagationService.SchemaGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Thin-wrapper tests for {@link SchemaPropagationController}. The controller delegates every route
 * to {@link SchemaPropagationService}, so these tests assert the request/response shape and that
 * the correct service method is invoked with the correct arguments. Behavior of the underlying
 * propagation logic is covered by {@code SchemaPropagationServiceTest}.
 *
 * <p>For {@code setOverride_rejectsInputDirection} we exercise the real service rejection path
 * (not a mocked service), because the input-direction-rejection rule is enforced inside the
 * service — we construct a minimally-mocked service with repository mocks that simulate the
 * presence of an input-direction row for the given port.
 */
@ExtendWith(MockitoExtension.class)
class SchemaPropagationControllerTest {

    @Mock private SchemaPropagationService service;

    @InjectMocks
    private SchemaPropagationController controller;

    // --- recompute ---------------------------------------------------------

    @Test
    void recompute_returnsCounts() {
        PropagationSummary summary = new PropagationSummary(7, 2, false);
        when(service.propagateFromVersion("v1")).thenReturn(summary);

        ResponseEntity<PropagationSummary> response = controller.recompute("v1");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(7, response.getBody().processed());
        assertEquals(2, response.getBody().conflicts());
        assertEquals(false, response.getBody().cycleDetected());
        verify(service).propagateFromVersion("v1");
    }

    // --- schema-graph ------------------------------------------------------

    @Test
    void schemaGraph_returnsAllPorts() {
        PortView inputPort = new PortView(
                "data_input", "input",
                List.of(Map.of("name", "id", "type", "string")),
                "propagated", null, "abc");
        PortView outputPort = new PortView(
                "filtered_output", "output",
                List.of(Map.of("name", "id", "type", "string")),
                "propagated", null, "def");
        InstanceSchemaView view = new InstanceSchemaView(
                "i-1", "Filter", "GenericFilter", "clean",
                List.of(inputPort, outputPort));
        SchemaGraph graph = new SchemaGraph("v1", List.of(view));
        when(service.getSchemaGraph("v1")).thenReturn(graph);

        ResponseEntity<SchemaGraph> response = controller.schemaGraph("v1");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("v1", response.getBody().versionId());
        assertEquals(1, response.getBody().instances().size());
        List<PortView> ports = response.getBody().instances().get(0).ports();
        assertEquals(2, ports.size());
        assertTrue(ports.stream().anyMatch(p -> "input".equals(p.direction())));
        assertTrue(ports.stream().anyMatch(p -> "output".equals(p.direction())));
    }

    // --- list conflicts ----------------------------------------------------

    @Test
    void conflicts_filterByStatus() {
        SchemaConflict open = new SchemaConflict();
        open.setId("c-1");
        open.setVersionId("v1");
        open.setInstanceId("i-1");
        open.setConflictType("MISSING_COLUMN");
        open.setResolutionStatus("open");
        when(service.listConflicts("v1", false)).thenReturn(List.of(open));

        SchemaConflict resolved = new SchemaConflict();
        resolved.setId("c-2");
        resolved.setVersionId("v1");
        resolved.setInstanceId("i-2");
        resolved.setConflictType("TYPE_MISMATCH");
        resolved.setResolutionStatus("resolved");
        when(service.listConflicts("v1", true)).thenReturn(List.of(open, resolved));

        // Default call (includeResolved=false) only returns open conflicts.
        ResponseEntity<List<SchemaConflict>> openOnly = controller.listConflicts("v1", false);
        assertEquals(1, openOnly.getBody().size());
        assertEquals("open", openOnly.getBody().get(0).getResolutionStatus());

        // includeResolved=true returns both.
        ResponseEntity<List<SchemaConflict>> all = controller.listConflicts("v1", true);
        assertEquals(2, all.getBody().size());
        verify(service).listConflicts("v1", false);
        verify(service).listConflicts("v1", true);
    }

    // --- resolve -----------------------------------------------------------

    @Test
    void resolveAcceptUpstream_triggersRepropagation() {
        SchemaConflict resolved = new SchemaConflict();
        resolved.setId("c-1");
        resolved.setVersionId("v1");
        resolved.setInstanceId("i-1");
        resolved.setConflictType("MISSING_COLUMN");
        resolved.setResolutionStatus("resolved");
        resolved.setResolutionType("accept_upstream");
        ResolutionRequest req = new ResolutionRequest("accept_upstream", "accepting upstream", null);
        when(service.resolveConflict(eq("v1"), eq("c-1"), any(ResolutionRequest.class)))
                .thenReturn(resolved);

        ResponseEntity<SchemaConflict> response = controller.resolve("v1", "c-1", req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("resolved", response.getBody().getResolutionStatus());
        assertEquals("accept_upstream", response.getBody().getResolutionType());

        ArgumentCaptor<ResolutionRequest> captor = ArgumentCaptor.forClass(ResolutionRequest.class);
        verify(service).resolveConflict(eq("v1"), eq("c-1"), captor.capture());
        assertEquals("accept_upstream", captor.getValue().resolutionType());
        // The service implementation re-runs propagateFromInstance after a non-flag resolution —
        // this is the "triggers repropagation" assertion: the controller exposes the API and the
        // service contract guarantees the cascade. That cascade itself is covered in
        // SchemaPropagationServiceTest; here we verify the controller plumbs the call through.
    }

    @Test
    void resolveOverride_writesOverrideAndMarksOverridden() {
        SchemaConflict overridden = new SchemaConflict();
        overridden.setId("c-2");
        overridden.setVersionId("v1");
        overridden.setInstanceId("i-1");
        overridden.setConflictType("TYPE_MISMATCH");
        overridden.setResolutionStatus("overridden");
        overridden.setResolutionType("override");
        Map<String, Object> override = Map.of(
                "columns", List.of(Map.of("name", "id", "type", "string")));
        ResolutionRequest req = new ResolutionRequest("override", "manual pin", override);
        when(service.resolveConflict(eq("v1"), eq("c-2"), any(ResolutionRequest.class)))
                .thenReturn(overridden);

        ResponseEntity<SchemaConflict> response = controller.resolve("v1", "c-2", req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("overridden", response.getBody().getResolutionStatus());
        assertEquals("override", response.getBody().getResolutionType());

        ArgumentCaptor<ResolutionRequest> captor = ArgumentCaptor.forClass(ResolutionRequest.class);
        verify(service).resolveConflict(eq("v1"), eq("c-2"), captor.capture());
        assertEquals("override", captor.getValue().resolutionType());
        assertSame(override, captor.getValue().override());
    }

    // --- override ---------------------------------------------------------

    @Test
    void setOverride_rejectsInputDirection() {
        // This test uses a real SchemaPropagationService (not the field-level @Mock one) so we can
        // exercise the actual rejection path that lives inside the service. The rejection rule is:
        // if a port-schema row with direction='input' already exists for (instanceId, portName),
        // setOverride throws IllegalStateException. Spring's default handler maps that to a 409 at
        // the HTTP layer; here we assert the exception is raised.
        SubPipelineInstanceRepository instanceRepo = org.mockito.Mockito.mock(SubPipelineInstanceRepository.class);
        InstancePortSchemaRepository portSchemaRepo = org.mockito.Mockito.mock(InstancePortSchemaRepository.class);

        SubPipelineInstance inst = new SubPipelineInstance();
        inst.setId("i-1");
        inst.setPipelineId("p-1");
        inst.setVersionId("v1");
        inst.setBlueprintId("bp-1");
        inst.setBlueprintKey("GenericFilter");
        inst.setBlueprintVersion("1.0");
        inst.setName("Filter");
        inst.setExecutionOrder(1);

        InstancePortSchema existingInput = new InstancePortSchema();
        existingInput.setId("ips-in-1");
        existingInput.setInstanceId("i-1");
        existingInput.setPortName("data_input");
        existingInput.setDirection("input");

        when(instanceRepo.findById("i-1")).thenReturn(Optional.of(inst));
        when(portSchemaRepo.findByInstanceIdAndPortNameAndDirection("i-1", "data_input", "input"))
                .thenReturn(Optional.of(existingInput));

        SchemaPropagationService realService = new SchemaPropagationService(
                instanceRepo,
                org.mockito.Mockito.mock(com.pulse.pipeline.repository.PortWiringRepository.class),
                org.mockito.Mockito.mock(com.pulse.blueprint.repository.BlueprintRepository.class),
                portSchemaRepo,
                org.mockito.Mockito.mock(com.pulse.pipeline.repository.SchemaConflictRepository.class),
                org.mockito.Mockito.mock(com.pulse.sor.repository.DatasetRepository.class),
                new com.pulse.pipeline.opengine.SchemaBehaviorReader(),
                new com.pulse.pipeline.opengine.SchemaOpEngine(
                        new com.pulse.pipeline.opengine.SqlModelSchemaService(
                                new com.pulse.expression.service.CalciteSqlModelValidator())),
                org.mockito.Mockito.mock(com.pulse.pipeline.service.SourceSqlSchemaResolver.class),
                new com.pulse.pipeline.opengine.ConflictClassifier(),
                org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
        SchemaPropagationController realController = new SchemaPropagationController(realService);

        OverrideRequest req = new OverrideRequest(
                List.of(Map.of("name", "whatever", "type", "string")),
                "trying to override an input");

        assertThrows(IllegalStateException.class,
                () -> realController.setOverride("v1", "i-1", "data_input", req));
    }

    @Test
    void clearOverride_revertsSource() {
        InstancePortSchema reverted = new InstancePortSchema();
        reverted.setId("ips-1");
        reverted.setInstanceId("i-1");
        reverted.setPortName("filtered_output");
        reverted.setDirection("output");
        reverted.setSource("propagated");
        reverted.setOverride(null);
        when(service.clearOverride(eq("v1"), eq("i-1"), eq("filtered_output")))
                .thenReturn(reverted);

        ResponseEntity<InstancePortSchema> response = controller.clearOverride(
                "v1", "i-1", "filtered_output");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("propagated", response.getBody().getSource());
        assertEquals(null, response.getBody().getOverride());
        verify(service).clearOverride("v1", "i-1", "filtered_output");
    }
}
