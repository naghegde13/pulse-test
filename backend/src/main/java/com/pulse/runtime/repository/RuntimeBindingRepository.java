package com.pulse.runtime.repository;

import com.pulse.runtime.model.RuntimeBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * PKT-FINAL-5 / BUG-39: Runtime bindings are deployment-global. Every
 * per-tenant lookup method ({@code findByTenantId*}) has been removed in
 * favor of global equivalents below. Callers that previously passed a
 * {@code tenantId} now read the same global rows regardless of which
 * tenant they are servicing.
 */
@Repository
public interface RuntimeBindingRepository extends JpaRepository<RuntimeBinding, String> {

    /** All bindings, ordered by environment, for the readiness verdict. */
    List<RuntimeBinding> findAllByOrderByEnvironmentAsc();

    /** All bindings for an environment, ordered by settings_role. */
    List<RuntimeBinding> findByEnvironmentOrderBySettingsRoleAsc(String environment);

    /** Convenience alias used by some readiness code paths. */
    List<RuntimeBinding> findByEnvironment(String environment);

    @Query("SELECT rb FROM RuntimeBinding rb WHERE rb.environment = :environment "
            + "AND rb.settingsRole = 'PRIMARY' AND rb.recordState = 'ACTIVE'")
    Optional<RuntimeBinding> findActivePrimary(String environment);

    @Query("SELECT rb FROM RuntimeBinding rb WHERE rb.environment = 'local' "
            + "AND rb.settingsRole = 'PRIMARY' AND rb.recordState = 'ACTIVE'")
    Optional<RuntimeBinding> findLocalProof();

    List<RuntimeBinding> findBySettingsRoleAndRecordState(String settingsRole, String recordState);
}
