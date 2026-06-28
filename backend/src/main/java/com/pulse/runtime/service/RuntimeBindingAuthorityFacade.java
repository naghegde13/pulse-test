package com.pulse.runtime.service;

import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.runtime.model.RuntimeBinding;
import com.pulse.runtime.repository.RuntimeBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The ONLY operational resolver for runtime bindings, deployment targets,
 * and storage roots.
 *
 * <p>PKT-FINAL-5 / BUG-39: Runtime bindings are deployment-global, so
 * {@code resolvePrimaryBinding(environment)} and
 * {@code resolveLocalProofBinding()} take no tenant parameter. Deployment
 * targets remain per-tenant (they govern who deploys what, not the runtime).
 *
 * <p>Every operational code path (codegen, deploy, packaging) MUST use this
 * facade instead of querying repositories directly. The facade guarantees
 * that DIAGNOSTIC rows are never returned as operational authority.
 *
 * <p>All resolution methods return a {@link ResolutionResult} — either
 * {@link ResolutionResult.Resolved} with the value, or
 * {@link ResolutionResult.Unresolved} with a blocker code and message.
 */
@Service
public class RuntimeBindingAuthorityFacade {

    private static final Logger log = LoggerFactory.getLogger(RuntimeBindingAuthorityFacade.class);

    private final RuntimeBindingRepository bindingRepository;
    private final DeploymentTargetRepository deploymentTargetRepository;

    public RuntimeBindingAuthorityFacade(RuntimeBindingRepository bindingRepository,
                                         DeploymentTargetRepository deploymentTargetRepository) {
        this.bindingRepository = bindingRepository;
        this.deploymentTargetRepository = deploymentTargetRepository;
    }

    // ------------------------------------------------------------------ resolution types

    /**
     * Sealed result type for runtime resolution — either a concrete value
     * or an unresolved blocker with a machine-readable code.
     */
    public sealed interface ResolutionResult<T>
            permits ResolutionResult.Resolved, ResolutionResult.Unresolved {

        record Resolved<T>(T value) implements ResolutionResult<T> {
        }

        record Unresolved<T>(String blockerCode, String message) implements ResolutionResult<T> {
        }
    }

    /**
     * Resolved storage roots for an environment (deployment-global).
     */
    public record StorageRoots(String files, String lake, String ops) {
    }

    // ------------------------------------------------------------------ binding resolution

    /**
     * PKT-FINAL-5 / BUG-39: Resolve the active PRIMARY binding for an
     * environment. Deployment-global — no tenant parameter.
     * DIAGNOSTIC rows are never returned as operational authority.
     */
    public ResolutionResult<RuntimeBinding> resolvePrimaryBinding(String environment) {
        Optional<RuntimeBinding> primary = bindingRepository.findActivePrimary(environment);
        if (primary.isEmpty()) {
            log.debug("No active PRIMARY binding for env={}", environment);
            return new ResolutionResult.Unresolved<>("binding_missing",
                    "No active PRIMARY runtime binding for deployment, environment="
                            + environment);
        }

        RuntimeBinding binding = primary.get();

        // Defence-in-depth: DIAGNOSTIC rows must never be surfaced
        // as operational authority, even if they pass the query filter.
        if (binding.isDiagnostic()) {
            log.warn("DIAGNOSTIC binding id={} passed query filter — rejecting as operational authority",
                    binding.getId());
            return new ResolutionResult.Unresolved<>("binding_missing",
                    "Only DIAGNOSTIC binding found for environment=" + environment
                            + "; operational PRIMARY required");
        }

        return new ResolutionResult.Resolved<>(binding);
    }

    /**
     * Resolve the local proof binding (environment = 'local', PRIMARY, ACTIVE).
     */
    public ResolutionResult<RuntimeBinding> resolveLocalProofBinding() {
        Optional<RuntimeBinding> local = bindingRepository.findLocalProof();
        if (local.isEmpty()) {
            log.debug("No local proof binding configured for deployment");
            return new ResolutionResult.Unresolved<>("binding_missing",
                    "No local proof binding configured for deployment");
        }

        RuntimeBinding binding = local.get();
        if (!binding.hasCompleteRoots()) {
            log.debug("Local proof binding id={} has incomplete roots", binding.getId());
            return new ResolutionResult.Unresolved<>("local_roots_incomplete",
                    "Local proof binding has incomplete storage roots");
        }

        return new ResolutionResult.Resolved<>(binding);
    }

    // ------------------------------------------------------------------ target resolution

    /**
     * Resolve the primary deployment target for a tenant + environment.
     *
     * <p>Deployment targets remain per-tenant (BUG-39 does not unify them).
     * Finds the first enabled target for the (tenant, environment) pair.
     * Targets with settings_role = 'DIAGNOSTIC' are excluded.
     */
    public ResolutionResult<DeploymentTarget> resolvePrimaryDeploymentTarget(String tenantId, String environment) {
        List<DeploymentTarget> targets = deploymentTargetRepository
                .findByTenantIdAndEnabledTrueOrderByEnvironmentAsc(tenantId);

        Optional<DeploymentTarget> match = targets.stream()
                .filter(t -> environment.equals(t.getEnvironment()))
                .findFirst();

        if (match.isEmpty()) {
            log.debug("No enabled deployment target for tenant={}, env={}", tenantId, environment);
            return new ResolutionResult.Unresolved<>("target_binding_missing",
                    "No enabled deployment target for tenant '" + tenantId
                            + "', environment '" + environment + "'");
        }

        return new ResolutionResult.Resolved<>(match.get());
    }

    // ------------------------------------------------------------------ storage roots resolution

    /**
     * Extract the three storage root paths from a binding entity directly.
     */
    public StorageRoots resolveStorageRoots(RuntimeBinding binding) {
        return new StorageRoots(
                binding.getStorageRootFiles(),
                binding.getStorageRootLake(),
                binding.getStorageRootOps());
    }

    /**
     * Resolve storage roots from the active PRIMARY binding for an
     * environment. Returns an {@link ResolutionResult.Unresolved} result
     * if the binding is missing or roots are incomplete.
     */
    public ResolutionResult<StorageRoots> resolveStorageRoots(String environment) {
        ResolutionResult<RuntimeBinding> bindingResult = resolvePrimaryBinding(environment);

        if (bindingResult instanceof ResolutionResult.Unresolved<RuntimeBinding> u) {
            return new ResolutionResult.Unresolved<>(u.blockerCode(), u.message());
        }

        RuntimeBinding binding = ((ResolutionResult.Resolved<RuntimeBinding>) bindingResult).value();
        StorageRoots roots = resolveStorageRoots(binding);
        return new ResolutionResult.Resolved<>(roots);
    }
}
