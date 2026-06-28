package com.pulse.runtime.service;

import com.pulse.runtime.model.RuntimeBinding;
import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.repository.RuntimeBindingRepository;
import com.pulse.storage.model.StorageBackend;
import com.pulse.storage.repository.StorageBackendRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PKT-FINAL-5 / BUG-39: Backfills deployment-global {@link RuntimeBinding}
 * rows from the legacy per-tenant {@code storage_backends} table.
 *
 * <p>Runtime bindings are now global, so the backfill consolidates per-tenant
 * storage_backends rows into a single binding per (environment, binding_kind).
 * Repeated backfill calls are idempotent — once a binding exists for a
 * group it's reused, not duplicated.
 *
 * <p>For each unique (environment, backend) tuple across all tenants:
 * <ul>
 *   <li>Creates a runtime binding with the matching kind (GCP or DPC).</li>
 *   <li>If the binding kind does not match the active persona,
 *       the row is demoted to DIAGNOSTIC with
 *       {@code diagnostic_reason = 'ARCH004_PERSONA_GUARD_DEMOTION'}.</li>
 *   <li>For the {@code local} environment, persona-first precedence
 *       applies — the kind matching the active persona gets PRIMARY,
 *       any opposite-family backend gets DIAGNOSTIC.</li>
 *   <li>Disabled storage backends are skipped.</li>
 * </ul>
 */
@Service
public class RuntimeBindingBackfillService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeBindingBackfillService.class);

    private static final String DEMOTION_REASON = "ARCH004_PERSONA_GUARD_DEMOTION";

    private final StorageBackendRepository storageBackendRepository;
    private final RuntimeBindingRepository bindingRepository;
    private final RuntimeAuthorityService runtimeAuthorityService;

    public RuntimeBindingBackfillService(StorageBackendRepository storageBackendRepository,
                                         RuntimeBindingRepository bindingRepository,
                                         RuntimeAuthorityService runtimeAuthorityService) {
        this.storageBackendRepository = storageBackendRepository;
        this.bindingRepository = bindingRepository;
        this.runtimeAuthorityService = runtimeAuthorityService;
    }

    // ------------------------------------------------------------------ result

    /**
     * Summary of a backfill run.
     */
    public record BackfillResult(int created, int demoted, int skipped,
                                 List<String> diagnosticMessages) {
    }

    // ------------------------------------------------------------------ backfill

    /**
     * Controller-facing variant that returns a plain map for JSON serialization.
     */
    @Transactional
    public Map<String, Object> backfillFromLegacyStorageBackends() {
        BackfillResult result = backfillFromStorageBackends();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("created", result.created());
        response.put("demoted", result.demoted());
        response.put("skipped", result.skipped());
        response.put("diagnosticMessages", result.diagnosticMessages());
        return response;
    }

    /**
     * PKT-FINAL-5 / BUG-39: walks every active storage_backends row in the
     * deployment, deduplicates by (environment, binding_kind, settings_role),
     * and creates one global binding per group. Pre-existing bindings are
     * preserved (idempotent re-run).
     */
    @Transactional
    public BackfillResult backfillFromStorageBackends() {
        List<StorageBackend> backends = storageBackendRepository.findAll();
        RuntimePersona activePersona = runtimeAuthorityService.getActivePersona();

        int created = 0;
        int demoted = 0;
        int skipped = 0;
        List<String> diagnosticMessages = new ArrayList<>();
        Set<String> seenGroups = new HashSet<>();

        // Pre-seed seenGroups with anything already in runtime_bindings so we
        // don't create duplicate rows on idempotent re-runs.
        for (RuntimeBinding existing : bindingRepository.findAll()) {
            seenGroups.add(groupKey(existing.getEnvironment(),
                    existing.getBindingKind(), existing.getSettingsRole()));
        }

        for (StorageBackend sb : backends) {
            if (sb.isDisabled()) {
                skipped++;
                diagnosticMessages.add("Skipped disabled storage backend: id=" + sb.getId()
                        + ", env=" + sb.getEnvironment() + ", backend=" + sb.getBackend());
                continue;
            }

            String bindingKind = mapBackendToBindingKind(sb.getBackend());
            if (bindingKind == null) {
                skipped++;
                diagnosticMessages.add("Skipped unknown backend type: id=" + sb.getId()
                        + ", backend=" + sb.getBackend());
                continue;
            }

            boolean isLocal = "local".equalsIgnoreCase(sb.getEnvironment());
            boolean alignsWithPersona = isAlignedWithPersona(bindingKind, activePersona);

            String settingsRole;
            String diagnosticReason = null;
            if (isLocal) {
                settingsRole = alignsWithPersona ? "PRIMARY" : "DIAGNOSTIC";
                if (!alignsWithPersona) {
                    diagnosticReason = DEMOTION_REASON;
                }
            } else if (alignsWithPersona) {
                settingsRole = "PRIMARY";
            } else {
                settingsRole = "DIAGNOSTIC";
                diagnosticReason = DEMOTION_REASON;
            }

            String key = groupKey(sb.getEnvironment(), bindingKind, settingsRole);
            if (seenGroups.contains(key)) {
                skipped++;
                diagnosticMessages.add("Skipped duplicate group (already bound): " + key
                        + " (source storage_backend_id=" + sb.getId() + ")");
                continue;
            }
            seenGroups.add(key);

            RuntimeBinding binding = new RuntimeBinding();
            binding.setEnvironment(sb.getEnvironment());
            binding.setBindingKind(bindingKind);
            binding.setSettingsRole(settingsRole);
            binding.setRecordState("ACTIVE");
            binding.setValidationStatus("PENDING");
            binding.setStorageRootFiles(sb.getStorageRootFiles());
            binding.setStorageRootLake(sb.getStorageRootLake());
            binding.setStorageRootOps(null); // storage_backends may not have ops root

            Map<String, Object> evidence = new HashMap<>();
            evidence.put("source", "storage_backends");
            evidence.put("storage_backend_id", sb.getId());
            evidence.put("original_backend", sb.getBackend());
            binding.setSourceEvidence(evidence);

            if (diagnosticReason != null) {
                binding.setDiagnosticReason(diagnosticReason);
                Map<String, Object> details = new HashMap<>();
                details.put("active_persona", activePersona.name());
                details.put("binding_kind", bindingKind);
                details.put("reason", "Binding kind does not match active persona");
                binding.setDiagnosticDetails(details);
            }

            bindingRepository.save(binding);
            created++;

            if ("DIAGNOSTIC".equals(settingsRole)) {
                demoted++;
                diagnosticMessages.add("Demoted to DIAGNOSTIC: env=" + sb.getEnvironment()
                        + ", kind=" + bindingKind + ", persona=" + activePersona
                        + ", reason=" + DEMOTION_REASON);
            }

            log.info("Backfilled deployment-global runtime binding: env={}, kind={}, role={}, source_backend_id={}",
                    sb.getEnvironment(), bindingKind, settingsRole, sb.getId());
        }

        log.info("PKT-FINAL-5 / BUG-39: backfill complete: created={}, demoted={}, skipped={}",
                created, demoted, skipped);
        return new BackfillResult(created, demoted, skipped, diagnosticMessages);
    }

    // ------------------------------------------------------------------ helpers

    private static String groupKey(String environment, String bindingKind, String settingsRole) {
        return Optional.ofNullable(environment).orElse("") + "|"
                + Optional.ofNullable(bindingKind).orElse("") + "|"
                + Optional.ofNullable(settingsRole).orElse("");
    }

    private String mapBackendToBindingKind(String backend) {
        if (backend == null) {
            return null;
        }
        return switch (backend.toUpperCase()) {
            case "GCP" -> "GCP";
            case "DPC" -> "DPC";
            default -> null;
        };
    }

    private boolean isAlignedWithPersona(String bindingKind, RuntimePersona persona) {
        return switch (persona) {
            case GCP_PULSE -> "GCP".equals(bindingKind);
            case DPC_PULSE -> "DPC".equals(bindingKind);
        };
    }
}
