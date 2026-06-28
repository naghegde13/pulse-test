package com.pulse.blueprint.repository;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BlueprintRepository extends JpaRepository<Blueprint, String> {

    List<Blueprint> findByCategoryOrderByNameAsc(BlueprintCategory category);

    List<Blueprint> findByDeferredFalseOrderByCategoryAscNameAsc();

    List<Blueprint> findAllByOrderByCategoryAscNameAsc();

    Optional<Blueprint> findByBlueprintKey(String blueprintKey);

    List<Blueprint> findByCategoryAndDeferredFalseOrderByNameAsc(BlueprintCategory category);

    List<Blueprint> findByStatusAndDeferredFalseOrderByCategoryAscNameAsc(String status);

    List<Blueprint> findByStatusAndCategoryAndDeferredFalseOrderByNameAsc(String status, BlueprintCategory category);

    // ----- ARCH-011 / ARCH-014: surface-aware queries ------------------------

    /** Active, non-deferred rows on the given add_surface. */
    List<Blueprint> findByStatusAndAddSurfaceAndDeferredFalseOrderByCategoryAscNameAsc(
            String status, String addSurface);

    /** Active, non-deferred rows on the given surface and category. */
    List<Blueprint> findByStatusAndCategoryAndAddSurfaceAndDeferredFalseOrderByNameAsc(
            String status, BlueprintCategory category, String addSurface);
}
