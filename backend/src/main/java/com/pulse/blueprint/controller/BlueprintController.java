package com.pulse.blueprint.controller;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.model.BlueprintSurface;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.codegen.service.CodegenExampleService;
import com.pulse.common.exception.ResourceNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only blueprint catalog endpoints.
 *
 * <p>Surface semantics (ARCH-011 / ARCH-014):
 * <ul>
 *   <li>{@code surface} omitted: default active, non-deferred rows across all
 *       surfaces (backward compatible).</li>
 *   <li>{@code surface=composition|orchestration_policy|none}: filter by
 *       {@code add_surface}.</li>
 *   <li>{@code surface=all}: every active, non-deferred row regardless of
 *       surface.</li>
 *   <li>{@code includeDeprecated=true}: include rows with
 *       {@code status=deprecated} (including ones also marked deferred). Does
 *       not include non-deprecated deferred rows.</li>
 *   <li>{@code includeDeferred=true}: include every row regardless of
 *       deferred/deprecated flags. Wins over {@code includeDeprecated}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/blueprints")
public class BlueprintController {

    private final BlueprintRepository blueprintRepository;
    private final CodegenExampleService codegenExampleService;

    public BlueprintController(BlueprintRepository blueprintRepository,
                               CodegenExampleService codegenExampleService) {
        this.blueprintRepository = blueprintRepository;
        this.codegenExampleService = codegenExampleService;
    }

    @GetMapping
    public ResponseEntity<List<Blueprint>> listBlueprints(
            @RequestParam(required = false) BlueprintCategory category,
            @RequestParam(required = false) String surface,
            @RequestParam(required = false, defaultValue = "false") boolean includeDeprecated,
            @RequestParam(required = false, defaultValue = "false") boolean includeDeferred) {

        BlueprintSurface surfaceFilter = BlueprintSurface.fromWire(surface);

        // includeDeferred wins: returns every row in repo order.
        if (includeDeferred) {
            List<Blueprint> all = (category != null)
                    ? blueprintRepository.findByCategoryOrderByNameAsc(category)
                    : blueprintRepository.findAllByOrderByCategoryAscNameAsc();
            return ResponseEntity.ok(applySurfaceFilter(all, surfaceFilter));
        }

        // Default base: active + non-deferred rows.
        List<Blueprint> base = (category != null)
                ? blueprintRepository.findByStatusAndCategoryAndDeferredFalseOrderByNameAsc("active", category)
                : blueprintRepository.findByStatusAndDeferredFalseOrderByCategoryAscNameAsc("active");

        if (includeDeprecated) {
            // Active base PLUS deprecated rows (including deprecated + deferred), no
            // non-deprecated deferred/experimental.
            List<Blueprint> deprecated = (category != null)
                    ? blueprintRepository.findByCategoryOrderByNameAsc(category).stream()
                            .filter(bp -> "deprecated".equalsIgnoreCase(bp.getStatus())).toList()
                    : blueprintRepository.findAllByOrderByCategoryAscNameAsc().stream()
                            .filter(bp -> "deprecated".equalsIgnoreCase(bp.getStatus())).toList();
            List<Blueprint> combined = new ArrayList<>(base.size() + deprecated.size());
            combined.addAll(base);
            for (Blueprint bp : deprecated) {
                if (combined.stream().noneMatch(b -> b.getBlueprintKey().equals(bp.getBlueprintKey()))) {
                    combined.add(bp);
                }
            }
            return ResponseEntity.ok(applySurfaceFilter(combined, surfaceFilter));
        }

        return ResponseEntity.ok(applySurfaceFilter(base, surfaceFilter));
    }

    @GetMapping("/{blueprintKey}")
    public ResponseEntity<Blueprint> getBlueprint(@PathVariable String blueprintKey) {
        Blueprint bp = blueprintRepository.findByBlueprintKey(blueprintKey)
                .orElseThrow(() -> new ResourceNotFoundException("Blueprint", blueprintKey));
        return ResponseEntity.ok(bp);
    }

    @GetMapping("/{blueprintKey}/examples")
    public ResponseEntity<List<com.pulse.codegen.service.CodegenExampleService.Example>> getExamples(
            @PathVariable String blueprintKey) {
        Blueprint bp = blueprintRepository.findByBlueprintKey(blueprintKey)
                .orElseThrow(() -> new ResourceNotFoundException("Blueprint", blueprintKey));
        if ("deprecated".equalsIgnoreCase(bp.getStatus()) || bp.isDeferred()) {
            return ResponseEntity.status(410).body(List.of());
        }
        return ResponseEntity.ok(codegenExampleService.getExamplesForBlueprint(blueprintKey));
    }

    // ----------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------

    private static List<Blueprint> applySurfaceFilter(List<Blueprint> rows, BlueprintSurface surface) {
        if (surface == null || surface == BlueprintSurface.ALL) {
            return rows;
        }
        String wire = surface.wire();
        return rows.stream()
                .filter(bp -> wire.equalsIgnoreCase(bp.getAddSurface()))
                .toList();
    }
}
