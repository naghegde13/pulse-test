package com.pulse.pipeline.service;

import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.storage.StorageBackendValidator;
import com.pulse.storage.model.LakeFormat;
import com.pulse.storage.model.LakeLayer;
import com.pulse.storage.model.StorageBackendType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns canonical-field extraction, validation, legacy-key stripping, and
 * persistence for {@link SubPipelineInstance} writes (ARCH-010).
 *
 * <p>All composition mutation paths (panel POST/PUT, chat plan/apply,
 * canonical PUT, legacy /params transition endpoint) must route writes
 * through this service so the canonical columns
 * ({@code storage_backend}, {@code lake_layer}, {@code lake_format}) are the
 * single source of truth and mirrored {@code params} keys are stripped on
 * every write.</p>
 *
 * <p>The service does not load or save instances itself; it just resolves
 * the {@link Resolution} that {@code CompositionService} applies to the
 * entity it owns. This keeps transaction boundaries with the JPA mutation
 * path that already exists.</p>
 */
@Service
public class BlueprintInstanceConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(BlueprintInstanceConfigurationService.class);

    /** Keys that must not survive on persisted {@code params}. */
    static final Set<String> MIRRORED_PARAM_KEYS =
            Set.of("storage_backend", "lake_layer", "lake_format");

    private final StorageBackendValidator validator;
    private final PipelineRepository pipelineRepository;

    public BlueprintInstanceConfigurationService(StorageBackendValidator validator,
                                                 PipelineRepository pipelineRepository) {
        this.validator = validator;
        this.pipelineRepository = pipelineRepository;
    }

    /**
     * Resolved canonical-field outcome for one instance write.
     *
     * @param storageBackend canonical storage backend value to persist.
     * @param lakeLayer canonical lake layer value (may be null).
     * @param lakeFormat canonical lake format value (may be null).
     * @param sanitizedParams params map with mirrored keys stripped.
     * @param deprecations one entry per mirrored key extracted from params,
     *                     surfaced via response metadata so callers can
     *                     migrate clients to top-level fields.
     * @param warnings non-fatal advisory messages.
     */
    public record Resolution(
            String storageBackend,
            String lakeLayer,
            String lakeFormat,
            Map<String, Object> sanitizedParams,
            List<String> deprecations,
            List<String> warnings) {

        public Resolution {
            sanitizedParams = sanitizedParams == null
                    ? Map.of()
                    : Map.copyOf(sanitizedParams);
            deprecations = deprecations == null ? List.of() : List.copyOf(deprecations);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * Resolves canonical fields for a fresh add. Missing
     * {@code storageBackend} is filled from the pipeline default.
     */
    public Resolution resolveForAdd(String pipelineId,
                                    String storageBackend,
                                    String lakeLayer,
                                    String lakeFormat,
                                    Map<String, Object> params) {
        return resolve(pipelineId, /*current=*/null, storageBackend, lakeLayer, lakeFormat, params);
    }

    /**
     * Resolves canonical fields for an update. {@code current} carries the
     * persisted values so omitted top-level fields preserve.
     */
    public Resolution resolveForUpdate(SubPipelineInstance current,
                                       String storageBackend,
                                       String lakeLayer,
                                       String lakeFormat,
                                       Map<String, Object> params) {
        if (current == null) {
            throw new IllegalArgumentException("Cannot resolve update without current instance");
        }
        return resolve(current.getPipelineId(), current, storageBackend, lakeLayer, lakeFormat, params);
    }

    private Resolution resolve(String pipelineId,
                               SubPipelineInstance current,
                               String storageBackend,
                               String lakeLayer,
                               String lakeFormat,
                               Map<String, Object> params) {

        List<String> deprecations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Map<String, Object> sanitized = (params == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(params);

        // ARCH-010: extract legacy mirrored keys from params, warn the caller,
        // strip them. Top-level fields always win over params-derived values.
        String paramBackend = extractParamKey(sanitized, "storage_backend", deprecations);
        String paramLayer = extractParamKey(sanitized, "lake_layer", deprecations);
        String paramFormat = extractParamKey(sanitized, "lake_format", deprecations);

        // Precedence: explicit top-level > legacy params > current entity > pipeline default.
        String resolvedBackend = firstNonBlank(
                storageBackend,
                paramBackend,
                current != null ? current.getStorageBackend() : null,
                resolvePipelineDefault(pipelineId));
        String resolvedLayer = firstNonBlank(
                lakeLayer,
                paramLayer,
                current != null ? current.getLakeLayer() : null);
        String resolvedFormat = firstNonBlank(
                lakeFormat,
                paramFormat,
                current != null ? current.getLakeFormat() : null);

        if (resolvedBackend == null || resolvedBackend.isBlank()) {
            throw new IllegalArgumentException(
                    "storage_backend is required (top-level field or pipeline default)");
        }

        // Validate against the (backend, layer, format) matrix.
        StorageBackendType backendType = StorageBackendType.from(resolvedBackend);
        LakeLayer layerEnum = LakeLayer.from(resolvedLayer);
        LakeFormat formatEnum = LakeFormat.from(resolvedFormat);
        validator.validate(backendType, layerEnum, formatEnum);

        return new Resolution(
                backendType.dbValue(),
                layerEnum != null ? layerEnum.dbValue() : null,
                formatEnum != null ? formatEnum.dbValue() : null,
                sanitized,
                deprecations,
                warnings);
    }

    /**
     * Applies a resolution to the entity. Caller is responsible for the JPA
     * save so transaction boundaries stay with the existing mutation path.
     */
    public void apply(SubPipelineInstance instance, Resolution resolution) {
        instance.setStorageBackend(resolution.storageBackend());
        instance.setLakeLayer(resolution.lakeLayer());
        instance.setLakeFormat(resolution.lakeFormat());
        instance.setParams(new LinkedHashMap<>(resolution.sanitizedParams()));
    }

    private String extractParamKey(Map<String, Object> sanitized,
                                   String key,
                                   List<String> deprecations) {
        if (!sanitized.containsKey(key)) {
            return null;
        }
        Object raw = sanitized.remove(key);
        deprecations.add(key);
        if (raw == null) {
            return null;
        }
        String s = raw.toString();
        return s.isBlank() ? null : s;
    }

    private String resolvePipelineDefault(String pipelineId) {
        if (pipelineId == null) {
            return null;
        }
        return pipelineRepository.findById(pipelineId)
                .map(Pipeline::getDefaultStorageBackend)
                .orElse(null);
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }
}
