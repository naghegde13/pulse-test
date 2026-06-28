package com.pulse.tenant.service;

import com.pulse.auth.service.TenantService;
import com.pulse.command.model.CommandLog;
import com.pulse.command.model.CommandStatus;
import com.pulse.command.repository.CommandLogRepository;
import com.pulse.tenant.model.TenantGcpRuntimeTopology;
import com.pulse.tenant.repository.TenantGcpRuntimeTopologyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PKT-FINAL-5 / BUG-54 / BUG-2026-05-26-58 (PKT-CAND-58): Per-tenant Secret
 * Manager binding management.
 *
 * <p>Stores the per-tenant secret authority mode (LOCAL_STUB |
 * GCP_SECRET_MANAGER | BLOCKED), the GSM project id, and an optional secret
 * name prefix on the existing {@link TenantGcpRuntimeTopology} row.
 * Avoids a separate table since the topology row already carries
 * per-resource project ids.
 *
 * <p>First PUT for a tenant defaults to {@code LOCAL_STUB} when no mode is
 * supplied. {@code GCP_SECRET_MANAGER} requires an explicit operator
 * opt-in with a non-blank {@code gsmProjectId} — the service rejects the
 * mode change otherwise.
 *
 * <p>Every mutation is journaled in {@code command_log} with
 * {@code aggregate_type=TENANT_SECRET_MANAGER_BINDING} so the operator
 * audit trail captures who flipped the mode and when.
 *
 * <p><b>Contract-drift bridge (PKT-CAND-58):</b> The frontend panel
 * (tenant-secret-manager-panel.tsx) uses {@code TENANT_GCP_SECRET_MANAGER}
 * on the wire while the DB / readiness services use {@code GCP_SECRET_MANAGER}.
 * {@link #normalizeMode(String)} accepts BOTH variants on input and stores
 * the canonical DB form ({@code GCP_SECRET_MANAGER}). {@link #buildReadback}
 * reverse-maps to the wire form ({@code TENANT_GCP_SECRET_MANAGER}) on GET so
 * the frontend's typed enum stays valid.
 */
@Service
public class TenantSecretManagerBindingService {

    private static final Logger log = LoggerFactory.getLogger(TenantSecretManagerBindingService.class);

    private static final String AGGREGATE_TYPE = "TENANT_SECRET_MANAGER_BINDING";
    private static final String COMMAND_TYPE = "tenant.secretManager.upsert";
    static final String MODE_LOCAL_STUB = "LOCAL_STUB";
    static final String MODE_GCP_SECRET_MANAGER = "GCP_SECRET_MANAGER";
    static final String MODE_BLOCKED = "BLOCKED";
    /** Wire-format alias the frontend panel uses for GCP_SECRET_MANAGER. */
    static final String WIRE_MODE_TENANT_GCP_SECRET_MANAGER = "TENANT_GCP_SECRET_MANAGER";

    private final TenantGcpRuntimeTopologyRepository topologyRepository;
    private final TenantService tenantService;
    private final CommandLogRepository commandLogRepository;

    public TenantSecretManagerBindingService(TenantGcpRuntimeTopologyRepository topologyRepository,
                                             TenantService tenantService,
                                             CommandLogRepository commandLogRepository) {
        this.topologyRepository = topologyRepository;
        this.tenantService = tenantService;
        this.commandLogRepository = commandLogRepository;
    }

    /**
     * Validation result for the secret manager binding request.
     */
    public record ValidationError(String code, String message) {
    }

    /**
     * Validate the incoming binding request. Returns empty when the request
     * is well-formed; otherwise returns a structured validation error the
     * controller can map to 400.
     */
    public Optional<ValidationError> validate(String mode, String gsmProjectId) {
        String normalized = normalizeMode(mode);
        if (normalized == null) {
            return Optional.of(new ValidationError("INVALID_MODE",
                    "mode must be one of: LOCAL_STUB, GCP_SECRET_MANAGER, "
                            + "TENANT_GCP_SECRET_MANAGER, BLOCKED"));
        }
        if (MODE_GCP_SECRET_MANAGER.equals(normalized)
                && (gsmProjectId == null || gsmProjectId.isBlank())) {
            return Optional.of(new ValidationError("MISSING_GSM_PROJECT_ID",
                    "gsmProjectId is required when mode=GCP_SECRET_MANAGER "
                            + "(or TENANT_GCP_SECRET_MANAGER)"));
        }
        return Optional.empty();
    }

    /**
     * Get the current binding for a tenant. Returns {@code Optional.empty}
     * only when the underlying topology row is missing entirely — when the
     * topology row exists without a mode, the column default (LOCAL_STUB)
     * applies.
     */
    public Optional<TenantGcpRuntimeTopology> getBinding(String tenantId) {
        return topologyRepository.findByTenantId(tenantId);
    }

    /**
     * Upsert the secret manager binding for a tenant. Validates tenant
     * existence + mode/project consistency before mutating. Journals a
     * {@link CommandLog} entry every PUT so the audit trail captures the
     * intent (idempotent no-mutation rePUTs are still logged).
     */
    @Transactional
    public TenantGcpRuntimeTopology upsert(String tenantId,
                                           String actorId,
                                           String mode,
                                           String gsmProjectId,
                                           String secretNamePrefix) {
        tenantService.getTenantEntity(tenantId); // throws if missing
        String normalized = normalizeMode(mode);
        if (normalized == null) {
            throw new IllegalArgumentException("Invalid mode: " + mode);
        }
        if (MODE_GCP_SECRET_MANAGER.equals(normalized)
                && (gsmProjectId == null || gsmProjectId.isBlank())) {
            throw new IllegalArgumentException(
                    "gsmProjectId required when mode=GCP_SECRET_MANAGER");
        }

        Optional<TenantGcpRuntimeTopology> existing = topologyRepository.findByTenantId(tenantId);
        TenantGcpRuntimeTopology target = existing.orElseGet(() -> {
            TenantGcpRuntimeTopology fresh = new TenantGcpRuntimeTopology();
            fresh.setTenantId(tenantId);
            // V148 column default is LOCAL_STUB; mirror on entity for in-memory tests.
            fresh.setSecretAuthorityMode(MODE_LOCAL_STUB);
            return fresh;
        });

        String previousMode = target.getSecretAuthorityMode();
        String previousProject = target.getSecretManagerProjectId();
        String previousPrefix = target.getSecretNamePrefix();

        target.setSecretAuthorityMode(normalized);
        // Only overwrite the project id when supplied — preserves co-existing
        // topology fields when the operator only flips the mode.
        if (gsmProjectId != null) {
            target.setSecretManagerProjectId(gsmProjectId.isBlank() ? null : gsmProjectId.trim());
        }
        if (secretNamePrefix != null) {
            target.setSecretNamePrefix(secretNamePrefix.isBlank() ? null : secretNamePrefix.trim());
        }

        TenantGcpRuntimeTopology saved = topologyRepository.save(target);

        boolean changed = !Objects.equals(previousMode, saved.getSecretAuthorityMode())
                || !Objects.equals(previousProject, saved.getSecretManagerProjectId())
                || !Objects.equals(previousPrefix, saved.getSecretNamePrefix());

        journalUpsert(tenantId, actorId, saved, previousMode, changed);

        log.info("PKT-FINAL-5 / BUG-54: Secret manager binding upsert tenant={} "
                        + "mode {} -> {} project={} changed={}",
                tenantId, previousMode, saved.getSecretAuthorityMode(),
                saved.getSecretManagerProjectId(), changed);

        return saved;
    }

    /**
     * Build a redaction-safe readback map for the binding. Never contains
     * secret material. Reverse-maps the canonical DB mode
     * ({@code GCP_SECRET_MANAGER}) to the wire mode the frontend panel
     * expects ({@code TENANT_GCP_SECRET_MANAGER}).
     */
    public Map<String, Object> buildReadback(String tenantId, TenantGcpRuntimeTopology topology) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", tenantId);
        String dbMode = topology.getSecretAuthorityMode() != null
                ? topology.getSecretAuthorityMode() : MODE_LOCAL_STUB;
        m.put("mode", toWireMode(dbMode));
        m.put("gsmProjectId", topology.getSecretManagerProjectId());
        m.put("secretNamePrefix", topology.getSecretNamePrefix());
        m.put("privateKeyRedacted", true);
        return m;
    }

    /**
     * Build the default readback for a tenant that has no topology row yet
     * (treated as LOCAL_STUB per the V148 column default).
     */
    public Map<String, Object> buildDefaultReadback(String tenantId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", tenantId);
        m.put("mode", MODE_LOCAL_STUB);
        m.put("gsmProjectId", null);
        m.put("secretNamePrefix", null);
        m.put("privateKeyRedacted", true);
        m.put("source", "default");
        return m;
    }

    /**
     * Bidirectional mode normalizer: maps wire / display / DB variants to the
     * canonical DB enum value. Accepts both {@code GCP_SECRET_MANAGER} (DB,
     * readiness services) and {@code TENANT_GCP_SECRET_MANAGER} (frontend
     * wire) so the contract drift is bridged transparently.
     *
     * <p>Returns {@code null} when the supplied mode is non-blank and does
     * not match any known variant (controller maps null → 400 INVALID_MODE).
     */
    static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_LOCAL_STUB;
        }
        String upper = mode.trim().toUpperCase();
        return switch (upper) {
            case "LOCAL_STUB", "LOCAL-STUB", "LOCALSTUB" -> MODE_LOCAL_STUB;
            case "GCP_SECRET_MANAGER", "GCP-SECRET-MANAGER", "TENANT_GCP_SECRET_MANAGER",
                 "TENANT-GCP-SECRET-MANAGER" -> MODE_GCP_SECRET_MANAGER;
            case "BLOCKED" -> MODE_BLOCKED;
            default -> null;
        };
    }

    /**
     * Reverse map of {@link #normalizeMode(String)}: maps the canonical DB
     * mode to the wire-format alias the frontend expects on GET responses.
     * Returns the input unchanged for LOCAL_STUB / BLOCKED.
     */
    static String toWireMode(String dbMode) {
        if (MODE_GCP_SECRET_MANAGER.equals(dbMode)) {
            return WIRE_MODE_TENANT_GCP_SECRET_MANAGER;
        }
        return dbMode;
    }

    private void journalUpsert(String tenantId,
                               String actorId,
                               TenantGcpRuntimeTopology saved,
                               String previousMode,
                               boolean changed) {
        CommandLog entry = new CommandLog();
        entry.setCommandType(COMMAND_TYPE);
        entry.setAggregateType(AGGREGATE_TYPE);
        entry.setAggregateId(saved.getId() != null ? saved.getId() : tenantId);
        entry.setTenantId(tenantId);
        entry.setActorId(actorId != null ? actorId : "system");
        // V148 idempotency key — one entry per tenant/timestamp tuple.
        entry.setIdempotencyKey("tenant-sm-binding:" + tenantId + ":"
                + Instant.now().toEpochMilli() + ":" + UUID.randomUUID());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", saved.getSecretAuthorityMode());
        payload.put("gsmProjectId", saved.getSecretManagerProjectId());
        payload.put("secretNamePrefix", saved.getSecretNamePrefix());
        payload.put("previousMode", previousMode);
        payload.put("changed", changed);
        entry.setPayload(payload);
        entry.setStatus(CommandStatus.SUCCEEDED);
        entry.setExecutedAt(Instant.now());
        commandLogRepository.save(entry);
    }
}
