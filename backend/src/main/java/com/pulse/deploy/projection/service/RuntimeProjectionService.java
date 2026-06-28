package com.pulse.deploy.projection.service;

import com.pulse.broker.mirror.RemoteTargetRuntimeMirror;
import com.pulse.broker.mirror.RemoteTargetRuntimeMirrorRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.projection.model.SourcePackageManifestV2;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.projection.model.RuntimeProjection;
import com.pulse.deploy.projection.model.RuntimeProjectionDdlStatement;
import com.pulse.deploy.projection.repository.RuntimeProjectionDdlStatementRepository;
import com.pulse.deploy.projection.repository.RuntimeProjectionRepository;
import com.pulse.runtime.model.RuntimeAuthority;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.runtime.service.RuntimeBindingAuthorityFacade;
import com.pulse.storage.contract.model.TableContract;
import com.pulse.storage.contract.service.DdlPlanService;
import com.pulse.storage.contract.service.TableContractService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class RuntimeProjectionService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeProjectionService.class);

    private final RuntimeProjectionRepository projectionRepository;
    private final RuntimeProjectionDdlStatementRepository ddlStatementRepository;
    private final PackageRepository packageRepository;
    private final DeploymentTargetRepository targetRepository;
    private final RemoteTargetRuntimeMirrorRepository runtimeMirrorRepository;
    private final RuntimeAuthorityService runtimeAuthorityService;
    private final RuntimeBindingAuthorityFacade bindingAuthorityFacade;
    private final TableContractService tableContractService;
    private final DdlPlanService ddlPlanService;

    public RuntimeProjectionService(RuntimeProjectionRepository projectionRepository,
                                     RuntimeProjectionDdlStatementRepository ddlStatementRepository,
                                     PackageRepository packageRepository,
                                     DeploymentTargetRepository targetRepository,
                                     RemoteTargetRuntimeMirrorRepository runtimeMirrorRepository,
                                     RuntimeAuthorityService runtimeAuthorityService,
                                     RuntimeBindingAuthorityFacade bindingAuthorityFacade,
                                     TableContractService tableContractService,
                                     DdlPlanService ddlPlanService) {
        this.projectionRepository = projectionRepository;
        this.ddlStatementRepository = ddlStatementRepository;
        this.packageRepository = packageRepository;
        this.targetRepository = targetRepository;
        this.runtimeMirrorRepository = runtimeMirrorRepository;
        this.runtimeAuthorityService = runtimeAuthorityService;
        this.bindingAuthorityFacade = bindingAuthorityFacade;
        this.tableContractService = tableContractService;
        this.ddlPlanService = ddlPlanService;
    }

    // ------------------------------------------------------------------ records

    public record ProjectionDriftResult(boolean drifted, String storedHash, String currentHash) {
    }

    // ------------------------------------------------------------------ public API

    /**
     * Create or refresh a runtime projection by deriving all contract
     * snapshots and DDL statements from product state.
     *
     * <p>PKT-0023: The projection endpoint no longer accepts caller-supplied
     * tableContracts or ddlStatements. All physical design proof is derived
     * from the package's versionId → active TableContract records →
     * DdlPlanService-generated DDL. This ensures runtime projections are
     * product-authority, not caller-supplied snapshots.
     */
    @Transactional
    public RuntimeProjection createOrRefresh(String packageId,
                                              String targetId,
                                              String environment) {
        // Load the package to derive table contracts from its versionId
        Package pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new ResourceNotFoundException("Package", packageId));

        // Derive table contracts from product state
        String versionId = pkg.getVersionId();
        List<TableContract> activeContracts = (versionId != null && !versionId.isBlank())
                ? tableContractService.findActiveContracts(versionId)
                : List.of();

        // Derive DDL plan from product state
        RuntimeAuthority authority = runtimeAuthorityService.getAuthority();
        String persona = authority.activePersona().name();
        String authorityVersion = authority.legalRuntimeMatrixVersion();

        List<DdlPlanService.DdlStatement> ddlPlan = activeContracts.isEmpty()
                ? List.of()
                : ddlPlanService.generateDdlPlan(activeContracts, persona, Map.of());

        // Map DdlPlanService.DdlStatement records to RuntimeProjectionDdlStatement entities
        List<RuntimeProjectionDdlStatement> ddlStatements = new ArrayList<>();
        for (DdlPlanService.DdlStatement planStmt : ddlPlan) {
            RuntimeProjectionDdlStatement entity = new RuntimeProjectionDdlStatement();
            entity.setStatementId(planStmt.statementId());
            entity.setPhase("schema".equals(planStmt.phase()) ? 1 : 2);
            entity.setExecutor(planStmt.executor());
            entity.setDialect(planStmt.dialect());
            entity.setBody(planStmt.body());
            entity.setSha256(planStmt.sha256());
            entity.setTableContractId(planStmt.tableContractId());
            entity.setIdempotencyMode(planStmt.idempotencyMode());
            ddlStatements.add(entity);
        }

        // Build contract version snapshot for hashing
        List<Map<String, Object>> contractSnapshots = activeContracts.stream()
                .map(c -> {
                    Map<String, Object> snap = new LinkedHashMap<>();
                    snap.put("id", c.getId());
                    snap.put("version", c.getContractVersion());
                    return snap;
                })
                .toList();

        // Supersede any existing active projection for same (packageId, targetId, env)
        Optional<RuntimeProjection> existing = projectionRepository
                .findByPackageIdAndTargetIdAndEnvironmentAndStatus(packageId, targetId, environment, "active");
        existing.ifPresent(p -> {
            p.setStatus("superseded");
            projectionRepository.save(p);
            log.info("Superseded existing projection id={} for package={}, target={}, env={}",
                    p.getId(), packageId, targetId, environment);
        });

        // Compute projection hash from product-derived data
        String contractVersions = contractSnapshots.stream()
                .map(c -> String.valueOf(c.getOrDefault("id", "")) + ":" + c.getOrDefault("version", ""))
                .sorted()
                .collect(Collectors.joining(","));
        String ddlHashes = ddlStatements.stream()
                .map(RuntimeProjectionDdlStatement::getSha256)
                .sorted()
                .collect(Collectors.joining(","));
        List<Map<String, Object>> brokerInvocations = brokerInvocations(packageId);
        List<Map<String, Object>> timeStateBindings = timeStateBindings(packageId);

        // Validate readiness blockers
        List<Map<String, Object>> readinessBlockers = validateReadiness(packageId, targetId, environment);
        Map<String, Object> resolvedEntrypoints = resolveBrokerEntrypoints(
                brokerInvocations, readinessBlockers);
        resolvedEntrypoints.put("timeStateBindings", resolveTimeStateBindings(timeStateBindings, readinessBlockers));
        resolveAirflowCallbackProjection(packageId, targetId, environment, readinessBlockers)
                .ifPresent(callback -> resolvedEntrypoints.put("airflowCallbacks", callback));
        String brokerHashInput = String.valueOf(resolvedEntrypoints);
        String projectionHash = sha256(packageId + "|" + targetId + "|" + environment
                + "|" + persona + "|" + contractVersions + "|" + ddlHashes + "|" + brokerHashInput);

        RuntimeProjection projection = new RuntimeProjection();
        projection.setPackageId(packageId);
        projection.setTargetId(targetId);
        projection.setEnvironment(environment);
        projection.setRuntimePersona(persona);
        projection.setRuntimeAuthorityVersion(authorityVersion);
        projection.setProjectionHash(projectionHash);
        projection.setStatus("active");
        projection.setReadinessBlockers(readinessBlockers);
        projection.setResolvedEntrypoints(resolvedEntrypoints);
        projection.setProjectedAt(Instant.now());

        RuntimeProjection saved = projectionRepository.save(projection);

        // Persist derived DDL statements with projection ID
        for (RuntimeProjectionDdlStatement stmt : ddlStatements) {
            stmt.setProjectionId(saved.getId());
            ddlStatementRepository.save(stmt);
        }

        log.info("Created runtime projection id={}, hash={}, contracts={}, ddlStmts={}, blockers={}",
                saved.getId(), projectionHash, activeContracts.size(),
                ddlStatements.size(), readinessBlockers.size());

        return saved;
    }

    public Optional<RuntimeProjection> getActiveProjection(String packageId, String targetId, String environment) {
        return projectionRepository
                .findByPackageIdAndTargetIdAndEnvironmentAndStatus(packageId, targetId, environment, "active");
    }

    public ProjectionDriftResult checkDrift(String projectionId) {
        RuntimeProjection projection = projectionRepository.findById(projectionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Projection not found: " + projectionId));

        // Derive current contract versions from product state (same path
        // as createOrRefresh) so the hash shape matches exactly.
        String versionId = packageRepository.findById(projection.getPackageId())
                .map(Package::getVersionId)
                .orElse(null);
        List<TableContract> activeContracts = (versionId != null && !versionId.isBlank())
                ? tableContractService.findActiveContracts(versionId)
                : List.of();
        String contractVersions = activeContracts.stream()
                .map(c -> c.getId() + ":" + c.getContractVersion())
                .sorted()
                .collect(Collectors.joining(","));

        List<RuntimeProjectionDdlStatement> statements =
                ddlStatementRepository.findByProjectionIdOrderByPhaseAsc(projectionId);

        String ddlHashes = statements.stream()
                .map(RuntimeProjectionDdlStatement::getSha256)
                .sorted()
                .collect(Collectors.joining(","));

        // Recompute hash with the same shape as createOrRefresh:
        // packageId|targetId|environment|persona|contractVersions|ddlHashes|brokerHashInput
        String brokerHashInput = projection.getResolvedEntrypoints() == null
                ? ""
                : String.valueOf(projection.getResolvedEntrypoints());
        String currentHash = sha256(projection.getPackageId() + "|" + projection.getTargetId()
                + "|" + projection.getEnvironment() + "|" + projection.getRuntimePersona()
                + "|" + contractVersions + "|" + ddlHashes + "|" + brokerHashInput);

        boolean drifted = !projection.getProjectionHash().equals(currentHash);

        if (drifted) {
            log.warn("Drift detected for projection id={}: stored={}, current={}",
                    projectionId, projection.getProjectionHash(), currentHash);
        }

        return new ProjectionDriftResult(drifted, projection.getProjectionHash(), currentHash);
    }

    // ------------------------------------------------------------------ internal

    private List<Map<String, Object>> validateReadiness(String packageId, String targetId, String environment) {
        List<Map<String, Object>> blockers = new ArrayList<>();

        // PKT-FINAL-5 / BUG-39: runtime bindings are deployment-global; no tenant arg.
        var bindingResult = bindingAuthorityFacade.resolvePrimaryBinding(environment);
        if (bindingResult instanceof RuntimeBindingAuthorityFacade.ResolutionResult.Unresolved<?> u) {
            blockers.add(Map.of(
                    "code", u.blockerCode(),
                    "message", u.message(),
                    "severity", "error"));
        }

        // Check deployment target availability
        var targetResult = bindingAuthorityFacade.resolvePrimaryDeploymentTarget(
                extractTenantContext(packageId), environment);
        if (targetResult instanceof RuntimeBindingAuthorityFacade.ResolutionResult.Unresolved<?> u) {
            blockers.add(Map.of(
                    "code", u.blockerCode(),
                    "message", u.message(),
                    "severity", "error"));
        }

        return blockers;
    }

    private String extractTenantContext(String packageId) {
        return packageRepository.findById(packageId)
                .map(Package::getTenantId)
                .orElse("default");
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> resolveAirflowCallbackProjection(String packageId,
                                                                           String targetId,
                                                                           String environment,
                                                                           List<Map<String, Object>> blockers) {
        Package pkg = packageRepository.findById(packageId).orElse(null);
        Map<String, Object> capabilityProfile = Map.of();
        if (pkg != null && pkg.getMetadata() != null) {
            Object manifest = pkg.getMetadata().get("packageManifest");
            if (manifest instanceof Map<?, ?> manifestMap) {
                Object capability = manifestMap.get("capabilityProfile");
                if (capability instanceof Map<?, ?> capabilityMap) {
                    capabilityProfile = new LinkedHashMap<>((Map<String, Object>) capabilityMap);
                }
            }
        }
        if (!SourcePackageManifestV2.isOptionalAirflowCallbackPolicy(capabilityProfile)) {
            return Optional.empty();
        }
        if (!SourcePackageManifestV2.isLocalOrDevEnvironment(environment)) {
            blockers.add(Map.of(
                    "code", "AIRFLOW_CALLBACK_POLICY_PROMOTED_ENV",
                    "message", "Airflow callback policy OPTIONAL is allowed only for local/dev projections.",
                    "severity", "error"));
            return Optional.empty();
        }

        DeploymentTarget target = targetRepository.findById(targetId).orElse(null);
        Map<String, Object> config = target == null || target.getConfig() == null
                ? Map.of()
                : target.getConfig();
        String callbackUrl = string(config.get("airflowCallbackUrl"));
        if (callbackUrl.isBlank()) {
            return Optional.empty();
        }
        if (callbackUrl.contains("localhost") || callbackUrl.contains("127.0.0.1")) {
            blockers.add(Map.of(
                    "code", "AIRFLOW_CALLBACK_LOCALHOST_FORBIDDEN",
                    "message", "Runtime projection must not use localhost fallback for Airflow callbacks.",
                    "severity", "error"));
            return Optional.empty();
        }
        if (callbackUrl.contains("PULSE_API_URL")) {
            blockers.add(Map.of(
                    "code", "AIRFLOW_CALLBACK_PULSE_API_URL_FORBIDDEN",
                    "message", "Runtime projection must not satisfy Airflow callback policy through PULSE_API_URL.",
                    "severity", "error"));
            return Optional.empty();
        }

        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("envVar", "PULSE_AIRFLOW_CALLBACK_URL");
        projected.put("airflowCallbackUrl", callbackUrl);
        projected.put("airflowCallbackTimeoutSeconds", positiveInt(config.get("airflowCallbackTimeoutSeconds"), 10));
        projected.put("airflowCallbackEvents", listOfStrings(config.get("airflowCallbackEvents"), List.of("success", "failure")));
        copyIfNonBlank(projected, "airflowCallbackAuthHeaderRef", config.get("airflowCallbackAuthHeaderRef"));
        copyIfNonBlank(projected, "airflowCallbackAuthSecretRef", config.get("airflowCallbackAuthSecretRef"));
        return Optional.of(projected);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> brokerInvocations(String packageId) {
        return packageRepository.findById(packageId)
                .map(Package::getMetadata)
                .map(meta -> meta == null ? null : meta.get("packageManifest"))
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(manifest -> manifest.get("entrypointCatalog"))
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(catalog -> catalog.get("brokerInvocations"))
                .filter(List.class::isInstance)
                .map(List.class::cast)
                .map(list -> (List<Map<String, Object>>) list)
                .orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> timeStateBindings(String packageId) {
        return packageRepository.findById(packageId)
                .map(Package::getMetadata)
                .map(meta -> meta == null ? null : meta.get("packageManifest"))
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(manifest -> manifest.get("entrypointCatalog"))
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(catalog -> catalog.get("timeStateBindings"))
                .filter(List.class::isInstance)
                .map(List.class::cast)
                .map(list -> (List<Map<String, Object>>) list)
                .orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveBrokerEntrypoints(List<Map<String, Object>> brokerInvocations,
                                                        List<Map<String, Object>> blockers) {
        if (brokerInvocations.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("brokerEdgeBindings", List.of());
            return empty;
        }
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (Map<String, Object> invocation : brokerInvocations) {
            String federatedTenantKey = string(invocation.get("federatedTenantKey"));
            String remoteTargetRef = string(invocation.get("remoteTargetRef"));
            String environment = string(invocation.get("environment"));
            Optional<RemoteTargetRuntimeMirror> mirror = runtimeMirrorRepository
                    .findFirstByFederatedTenantKeyAndRemoteTargetRefAndEnvironment(
                            federatedTenantKey, remoteTargetRef, environment);
            if (mirror.isEmpty()) {
                blockers.add(Map.of(
                        "code", "BROKER_TARGET_RUNTIME_MIRROR_MISSING",
                        "message", "No runtime mirror for remote target " + remoteTargetRef
                                + " in env " + environment,
                        "severity", "error",
                        "instanceId", string(invocation.get("instanceId"))));
                continue;
            }
            RemoteTargetRuntimeMirror row = mirror.get();
            Map<String, Object> runtimePayload = row.getPayload() == null ? Map.of() : row.getPayload();
            Map<String, Object> runtimeMetadata = runtimePayload.get("runtimeMetadata") instanceof Map<?, ?> m
                    ? new LinkedHashMap<>((Map<String, Object>) m)
                    : Map.of();
            String airflowConnectionId = firstNonBlank(
                    string(invocation.get("airflowConnectionId")),
                    string(invocation.get("airflow_connection_id")),
                    string(runtimeMetadata.get("airflowConnectionId")),
                    string(runtimePayload.get("airflowConnectionId")));
            String remoteDagId = firstNonBlank(
                    string(invocation.get("remoteDagId")),
                    string(invocation.get("remote_dag_id")),
                    row.getPeerLogicalDagId(),
                    string(runtimePayload.get("peerLogicalDagId")));
            if (airflowConnectionId.isBlank()) {
                blockers.add(Map.of(
                        "code", "BROKER_AIRFLOW_CONNECTION_MISSING",
                        "message", "Remote target " + remoteTargetRef
                                + " requires an Airflow connection ID in the package or runtime mirror",
                        "severity", "error",
                        "instanceId", string(invocation.get("instanceId"))));
            }
            if (remoteDagId.isBlank()) {
                blockers.add(Map.of(
                        "code", "BROKER_REMOTE_DAG_ID_MISSING",
                        "message", "Remote target " + remoteTargetRef
                                + " has no remote Airflow DAG ID",
                        "severity", "error",
                        "instanceId", string(invocation.get("instanceId"))));
            }
            bindings.add(Map.of(
                    "instanceId", string(invocation.get("instanceId")),
                    "federatedTenantKey", row.getFederatedTenantKey(),
                    "remoteTargetRef", row.getRemoteTargetRef(),
                    "environment", row.getEnvironment(),
                    "trustBindingId", row.getTrustBindingId(),
                    "remoteDagId", remoteDagId,
                    "airflowConnectionId", airflowConnectionId,
                    "pollIntervalSeconds", firstPositive(
                            intValue(invocation.get("pollIntervalSeconds"), 0),
                            intValue(runtimeMetadata.get("pollIntervalSeconds"), 0),
                            intValue(runtimePayload.get("pollIntervalSeconds"), 0),
                            30),
                    "timeoutSeconds", firstPositive(
                            intValue(invocation.get("timeoutSeconds"), 0),
                            intValue(runtimeMetadata.get("timeoutSeconds"), 0),
                            intValue(runtimePayload.get("timeoutSeconds"), 0),
                            7200),
                    "runtimeMirrorId", row.getId()));
        }
        if (bindings.size() != brokerInvocations.size()) {
            blockers.add(Map.of(
                    "code", "BROKER_EDGE_BINDINGS_INCOMPLETE",
                    "message", "Runtime projection is missing one or more broker edge bindings",
                    "severity", "error"));
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        resolved.put("brokerEdgeBindings", bindings);
        return resolved;
    }

    private List<Map<String, Object>> resolveTimeStateBindings(List<Map<String, Object>> timeStateBindings,
                                                               List<Map<String, Object>> blockers) {
        if (timeStateBindings.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> resolved = new ArrayList<>();
        for (Map<String, Object> binding : timeStateBindings) {
            if (string(binding.get("stateBindingRef")).isBlank()
                    || string(binding.get("variableKey")).isBlank()
                    || string(binding.get("calendarBindingRef")).isBlank()
                    || string(binding.get("calendarBundleUri")).isBlank()
                    || string(binding.get("evidencePrefix")).isBlank()
                    || string(binding.get("initializationPolicy")).isBlank()
                    || string(binding.get("concurrencyPolicy")).isBlank()) {
                blockers.add(Map.of(
                        "code", "TIME_STATE_BINDING_INCOMPLETE",
                        "message", "AdvanceTimeDimension binding is missing runtime projection fields",
                        "severity", "error",
                        "instanceId", string(binding.get("instanceId"))));
                continue;
            }
            resolved.add(new LinkedHashMap<>(binding));
        }
        return resolved;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static int positiveInt(Object raw, int defaultValue) {
        if (raw instanceof Number n && n.intValue() > 0) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                int parsed = Integer.parseInt(s.trim());
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return defaultValue;
    }

    private static List<String> listOfStrings(Object raw, List<String> defaultValue) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? null : String.valueOf(item))
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
        }
        return defaultValue;
    }

    private static void copyIfNonBlank(Map<String, Object> target, String key, Object raw) {
        String value = string(raw);
        if (!value.isBlank()) {
            target.put(key, value);
        }
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int firstPositive(int... values) {
        for (int value : values) {
            if (value > 0) return value;
        }
        return 0;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
