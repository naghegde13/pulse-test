package com.pulse.runtime.service;

import com.pulse.runtime.model.RuntimeBinding;
import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.repository.RuntimeBindingRepository;
import com.pulse.runtime.service.RuntimeAuthorityService.RuntimeAuthorityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * PKT-FINAL-5 / BUG-39: CRUD and lifecycle management for deployment-global
 * {@link RuntimeBinding} entities.
 *
 * <p>On create/update/activate to PRIMARY for non-local environments,
 * validates that the binding kind aligns with the active runtime persona
 * (GCP_PULSE → GCP only, DPC_PULSE → DPC only; LOCAL is always allowed
 * for the {@code local} environment).
 */
@Service
public class RuntimeBindingService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeBindingService.class);

    private final RuntimeBindingRepository bindingRepository;
    private final RuntimeAuthorityService runtimeAuthorityService;

    public RuntimeBindingService(RuntimeBindingRepository bindingRepository,
                                 RuntimeAuthorityService runtimeAuthorityService) {
        this.bindingRepository = bindingRepository;
        this.runtimeAuthorityService = runtimeAuthorityService;
    }

    // ------------------------------------------------------------------ query

    public List<RuntimeBinding> listGlobal() {
        return bindingRepository.findAllByOrderByEnvironmentAsc();
    }

    public List<RuntimeBinding> listForEnvironment(String environment) {
        return bindingRepository.findByEnvironmentOrderBySettingsRoleAsc(environment);
    }

    // ------------------------------------------------------------------ create

    /**
     * Persist a pre-populated entity (used by the controller).
     */
    @Transactional
    public RuntimeBinding create(RuntimeBinding binding) {
        if (binding.isPrimary() && !isLocalEnvironment(binding.getEnvironment())) {
            validatePrimaryPersonaAlignment(binding.getBindingKind(), binding.getEnvironment());
        }
        RuntimeBinding saved = bindingRepository.save(binding);
        log.info("Created runtime binding: id={}, env={}, kind={}, role={}",
                saved.getId(), saved.getEnvironment(),
                saved.getBindingKind(), saved.getSettingsRole());
        return saved;
    }

    @Transactional
    public RuntimeBinding createBinding(String environment,
                                        String bindingKind,
                                        String settingsRole,
                                        String storageRootFiles,
                                        String storageRootLake,
                                        String storageRootOps) {

        RuntimeBinding binding = new RuntimeBinding();
        binding.setEnvironment(environment);
        binding.setBindingKind(bindingKind);
        binding.setSettingsRole(settingsRole != null ? settingsRole : "PRIMARY");
        binding.setRecordState("ACTIVE");
        binding.setValidationStatus("PENDING");
        binding.setStorageRootFiles(storageRootFiles);
        binding.setStorageRootLake(storageRootLake);
        binding.setStorageRootOps(storageRootOps);

        return create(binding);
    }

    // ------------------------------------------------------------------ update

    /**
     * Persist an already-mutated entity (used by the controller).
     */
    @Transactional
    public RuntimeBinding update(RuntimeBinding binding) {
        if (binding.isPrimary() && !isLocalEnvironment(binding.getEnvironment())) {
            validatePrimaryPersonaAlignment(binding.getBindingKind(), binding.getEnvironment());
        }

        RuntimeBinding saved = bindingRepository.save(binding);
        log.info("Updated runtime binding: id={}, env={}",
                saved.getId(), saved.getEnvironment());
        return saved;
    }

    @Transactional
    public RuntimeBinding updateBinding(String id, Map<String, Object> patch) {
        RuntimeBinding binding = findOrThrow(id);

        if (patch.containsKey("storageRootFiles")) {
            binding.setStorageRootFiles((String) patch.get("storageRootFiles"));
        }
        if (patch.containsKey("storageRootLake")) {
            binding.setStorageRootLake((String) patch.get("storageRootLake"));
        }
        if (patch.containsKey("storageRootOps")) {
            binding.setStorageRootOps((String) patch.get("storageRootOps"));
        }
        if (patch.containsKey("bindingKind")) {
            binding.setBindingKind((String) patch.get("bindingKind"));
        }
        if (patch.containsKey("settingsRole")) {
            binding.setSettingsRole((String) patch.get("settingsRole"));
        }
        if (patch.containsKey("diagnosticReason")) {
            binding.setDiagnosticReason((String) patch.get("diagnosticReason"));
        }
        if (patch.containsKey("diagnosticDetails")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) patch.get("diagnosticDetails");
            binding.setDiagnosticDetails(details);
        }

        return update(binding);
    }

    // ------------------------------------------------------------------ activate / deactivate

    @Transactional
    public RuntimeBinding activateBinding(String id) {
        RuntimeBinding binding = findOrThrow(id);
        binding.setRecordState("ACTIVE");

        if (binding.isPrimary() && !isLocalEnvironment(binding.getEnvironment())) {
            validatePrimaryPersonaAlignment(binding.getBindingKind(), binding.getEnvironment());
        }

        RuntimeBinding saved = bindingRepository.save(binding);
        log.info("Activated runtime binding: id={}, env={}",
                saved.getId(), saved.getEnvironment());
        return saved;
    }

    @Transactional
    public RuntimeBinding deactivateBinding(String id) {
        RuntimeBinding binding = findOrThrow(id);
        binding.setRecordState("INACTIVE");

        RuntimeBinding saved = bindingRepository.save(binding);
        log.info("Deactivated runtime binding: id={}, env={}",
                saved.getId(), saved.getEnvironment());
        return saved;
    }

    // ------------------------------------------------------------------ persona alignment

    private void validatePrimaryPersonaAlignment(String bindingKind, String environment) {
        RuntimePersona activePersona = runtimeAuthorityService.getActivePersona();

        boolean allowed = switch (activePersona) {
            case GCP_PULSE -> "GCP".equals(bindingKind);
            case DPC_PULSE -> "DPC".equals(bindingKind);
        };

        if (!allowed) {
            throw new RuntimeAuthorityViolationException(
                    "Binding kind '" + bindingKind + "' for environment '" + environment
                            + "' is not aligned with active persona " + activePersona
                            + "; expected " + expectedKindForPersona(activePersona));
        }
    }

    private String expectedKindForPersona(RuntimePersona persona) {
        return switch (persona) {
            case GCP_PULSE -> "GCP";
            case DPC_PULSE -> "DPC";
        };
    }

    private boolean isLocalEnvironment(String environment) {
        return "local".equalsIgnoreCase(environment);
    }

    private RuntimeBinding findOrThrow(String id) {
        return bindingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Runtime binding not found: " + id));
    }
}
