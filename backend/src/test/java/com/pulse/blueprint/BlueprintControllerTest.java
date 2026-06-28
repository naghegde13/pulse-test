package com.pulse.blueprint;

import com.pulse.blueprint.controller.BlueprintController;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlueprintControllerTest {

    @Mock private BlueprintRepository blueprintRepository;
    @Mock private com.pulse.codegen.service.CodegenExampleService codegenExampleService;

    @InjectMocks
    private BlueprintController controller;

    // -----------------------------------------------------------------------
    //  default list behavior — backward compatible
    // -----------------------------------------------------------------------

    @Test
    void list_returnsActiveBlueprints() {
        Blueprint bp1 = bp("SnapshotIngestion", BlueprintCategory.INGESTION, "composition", false, "active");
        Blueprint bp2 = bp("GenericJoin", BlueprintCategory.TRANSFORM, "composition", false, "active");
        when(blueprintRepository.findByStatusAndDeferredFalseOrderByCategoryAscNameAsc("active"))
                .thenReturn(List.of(bp1, bp2));

        ResponseEntity<List<Blueprint>> response = controller.listBlueprints(null, null, false, false);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().size());
        verify(blueprintRepository).findByStatusAndDeferredFalseOrderByCategoryAscNameAsc("active");
    }

    @Test
    void list_includeDeferred_returnsAllIncludingDeferred() {
        Blueprint bp1 = bp("SnapshotIngestion", BlueprintCategory.INGESTION, "composition", false, "active");
        Blueprint bpDeferred = bp("DeferredBP", BlueprintCategory.TRANSFORM, "none", true, "active");
        when(blueprintRepository.findAllByOrderByCategoryAscNameAsc())
                .thenReturn(List.of(bp1, bpDeferred));

        ResponseEntity<List<Blueprint>> response = controller.listBlueprints(null, null, false, true);

        assertEquals(2, response.getBody().size());
        verify(blueprintRepository).findAllByOrderByCategoryAscNameAsc();
    }

    @Test
    void list_withCategoryFilter_returnsFilteredActiveBlueprints() {
        Blueprint bpRow = bp("SnapshotIngestion", BlueprintCategory.INGESTION, "composition", false, "active");
        when(blueprintRepository.findByStatusAndCategoryAndDeferredFalseOrderByNameAsc(
                "active", BlueprintCategory.INGESTION))
                .thenReturn(List.of(bpRow));

        ResponseEntity<List<Blueprint>> response = controller.listBlueprints(
                BlueprintCategory.INGESTION, null, false, false);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        verify(blueprintRepository).findByStatusAndCategoryAndDeferredFalseOrderByNameAsc(
                "active", BlueprintCategory.INGESTION);
    }

    // -----------------------------------------------------------------------
    //  surface filtering (ARCH-011 / ARCH-012)
    // -----------------------------------------------------------------------

    @Test
    void list_surfaceComposition_filtersOutPolicy() {
        Blueprint comp = bp("FileIngestion", BlueprintCategory.INGESTION, "composition", false, "active");
        Blueprint policy = bp("ScheduleAndTriggers", BlueprintCategory.ORCHESTRATION,
                "orchestration_policy", false, "active");
        when(blueprintRepository.findByStatusAndDeferredFalseOrderByCategoryAscNameAsc("active"))
                .thenReturn(List.of(comp, policy));

        ResponseEntity<List<Blueprint>> response = controller.listBlueprints(null, "composition", false, false);

        assertEquals(1, response.getBody().size());
        assertEquals("FileIngestion", response.getBody().get(0).getBlueprintKey());
    }

    @Test
    void list_surfaceOrchestrationPolicy_returnsOnlyPolicy() {
        Blueprint comp = bp("FileIngestion", BlueprintCategory.INGESTION, "composition", false, "active");
        Blueprint policy = bp("ScheduleAndTriggers", BlueprintCategory.ORCHESTRATION,
                "orchestration_policy", false, "active");
        when(blueprintRepository.findByStatusAndDeferredFalseOrderByCategoryAscNameAsc("active"))
                .thenReturn(List.of(comp, policy));

        ResponseEntity<List<Blueprint>> response = controller.listBlueprints(null, "orchestration_policy", false, false);

        assertEquals(1, response.getBody().size());
        assertEquals("ScheduleAndTriggers", response.getBody().get(0).getBlueprintKey());
    }

    @Test
    void list_surfaceAll_returnsAllSurfaces() {
        Blueprint comp = bp("FileIngestion", BlueprintCategory.INGESTION, "composition", false, "active");
        Blueprint policy = bp("ScheduleAndTriggers", BlueprintCategory.ORCHESTRATION,
                "orchestration_policy", false, "active");
        when(blueprintRepository.findByStatusAndDeferredFalseOrderByCategoryAscNameAsc("active"))
                .thenReturn(List.of(comp, policy));

        ResponseEntity<List<Blueprint>> response = controller.listBlueprints(null, "all", false, false);

        assertEquals(2, response.getBody().size());
    }

    // -----------------------------------------------------------------------
    //  includeDeprecated (ARCH-014)
    // -----------------------------------------------------------------------

    @Test
    void list_includeDeprecated_addsDeprecatedRowsToActiveBase() {
        Blueprint active = bp("FileIngestion", BlueprintCategory.INGESTION, "composition", false, "active");
        Blueprint deprecatedRow = bp("Reconciliation", BlueprintCategory.DATA_QUALITY, "none", true, "deprecated");
        Blueprint deferredOnly = bp("Derive", BlueprintCategory.TRANSFORM, "none", true, "active");
        when(blueprintRepository.findByStatusAndDeferredFalseOrderByCategoryAscNameAsc("active"))
                .thenReturn(List.of(active));
        when(blueprintRepository.findAllByOrderByCategoryAscNameAsc())
                .thenReturn(List.of(active, deprecatedRow, deferredOnly));

        ResponseEntity<List<Blueprint>> response = controller.listBlueprints(null, "all", true, false);

        // Active row plus the deprecated row; the non-deprecated deferred row stays hidden.
        assertEquals(2, response.getBody().size());
        assertTrue(response.getBody().stream().anyMatch(b -> "Reconciliation".equals(b.getBlueprintKey())));
        assertFalse(response.getBody().stream().anyMatch(b -> "Derive".equals(b.getBlueprintKey())));
    }

    @Test
    void list_includeDeferred_winsOverIncludeDeprecated() {
        Blueprint active = bp("FileIngestion", BlueprintCategory.INGESTION, "composition", false, "active");
        Blueprint deferredOnly = bp("Derive", BlueprintCategory.TRANSFORM, "none", true, "active");
        Blueprint deprecatedRow = bp("Reconciliation", BlueprintCategory.DATA_QUALITY, "none", true, "deprecated");
        when(blueprintRepository.findAllByOrderByCategoryAscNameAsc())
                .thenReturn(List.of(active, deferredOnly, deprecatedRow));

        ResponseEntity<List<Blueprint>> response = controller.listBlueprints(null, "all", true, true);

        // includeDeferred returns every row.
        assertEquals(3, response.getBody().size());
        verify(blueprintRepository).findAllByOrderByCategoryAscNameAsc();
    }

    // -----------------------------------------------------------------------
    //  get by id tests
    // -----------------------------------------------------------------------

    @Test
    void get_returnsBlueprintByKey() {
        Blueprint bp = bp("SnapshotIngestion", BlueprintCategory.INGESTION, "composition", false, "active");
        when(blueprintRepository.findByBlueprintKey("SnapshotIngestion"))
                .thenReturn(Optional.of(bp));

        ResponseEntity<Blueprint> response = controller.getBlueprint("SnapshotIngestion");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("SnapshotIngestion", response.getBody().getBlueprintKey());
    }

    @Test
    void get_notFound_throwsException() {
        when(blueprintRepository.findByBlueprintKey("Nonexistent")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> controller.getBlueprint("Nonexistent"));
    }

    // -----------------------------------------------------------------------
    //  PKT-0006: examples endpoint rejects deprecated and deferred
    // -----------------------------------------------------------------------

    @Test
    void getExamples_deprecatedBlueprint_returns410() {
        Blueprint deprecated = bp("Reconciliation", BlueprintCategory.DATA_QUALITY, "none", true, "deprecated");
        when(blueprintRepository.findByBlueprintKey("Reconciliation"))
                .thenReturn(Optional.of(deprecated));

        ResponseEntity<?> response = controller.getExamples("Reconciliation");

        assertEquals(410, response.getStatusCode().value());
    }

    @Test
    void getExamples_deferredBlueprint_returns410() {
        Blueprint deferred = bp("Derive", BlueprintCategory.TRANSFORM, "none", true, "active");
        when(blueprintRepository.findByBlueprintKey("Derive"))
                .thenReturn(Optional.of(deferred));

        ResponseEntity<?> response = controller.getExamples("Derive");

        assertEquals(410, response.getStatusCode().value());
    }

    @Test
    void getExamples_activeBlueprint_returns200() {
        Blueprint active = bp("FileIngestion", BlueprintCategory.INGESTION, "composition", false, "active");
        when(blueprintRepository.findByBlueprintKey("FileIngestion"))
                .thenReturn(Optional.of(active));
        when(codegenExampleService.getExamplesForBlueprint("FileIngestion"))
                .thenReturn(List.of());

        ResponseEntity<?> response = controller.getExamples("FileIngestion");

        assertEquals(200, response.getStatusCode().value());
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private Blueprint bp(String key, BlueprintCategory category,
                         String addSurface, boolean deferred, String status) {
        Blueprint bp = new Blueprint();
        bp.setId("bp-" + key);
        bp.setBlueprintKey(key);
        bp.setName(key + " Blueprint");
        bp.setDescription("Test blueprint for " + key);
        bp.setCategory(category);
        bp.setVersion("1.0");
        bp.setDeferred(deferred);
        bp.setPipelineConfig("orchestration_policy".equals(addSurface));
        bp.setStatus(status);
        bp.setAddSurface(addSurface);
        return bp;
    }
}
