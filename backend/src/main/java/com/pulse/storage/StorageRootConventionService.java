package com.pulse.storage;

import com.pulse.auth.service.TenantService;
import com.pulse.config.TenantConfig.TenantDefinition;
import com.pulse.storage.model.StorageBackendType;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * SU-6 / BUG-62: Single source of truth for the V96 storage-root naming
 * convention.
 *
 * <p>The V96 migration seeds {@code storage_backends} with deterministic
 * default values (see {@code V96__storage_backends_and_pipeline_storage_columns.sql}):
 *
 * <pre>
 *   GCP files:   pulse-{slug}-{env}-files
 *   GCP lake:    pulse-{slug}-{env}-lake
 *   GCP project: pulse-{slug}-{env}
 *   DPC files:   pulse-dpc-{slug}-{env}-files
 *   DPC lake:    pulse-dpc-{slug}-{env}-lake
 *   DPC cluster: pulse-dpc-{slug}-{env}
 *   DPC scheme:  s3a (default)
 * </pre>
 *
 * <p>Before this service, that convention lived in three places:
 *
 * <ol>
 *   <li>V96 migration (above) — the actual seeded rows.</li>
 *   <li>The frontend storage-backends panel — used illustratively in the
 *       "Path preview" examples block.</li>
 *   <li>Onboarding documentation / runbooks — copy-pasted from V96.</li>
 * </ol>
 *
 * <p>This service consolidates the convention into one Java method so
 * the new {@code /api/v1/tenants/{tenantId}/storage-backends/conventional}
 * endpoint can return defaults that the Edit dialog can pre-populate
 * (BUG-62), and so future surfaces (codegen, package builder) can read
 * the convention without re-deriving it.
 *
 * <p>The service is pure — no DB writes, no side effects. It needs
 * {@link TenantService} only to resolve a tenant id to its slug.
 */
@Service
public class StorageRootConventionService {

    /**
     * V96 supports four environments. Kept here as the source-of-truth set
     * so the convention endpoint can validate {@code ?env=…} inputs without
     * reaching into the persistence layer.
     */
    public static final Set<String> SUPPORTED_ENVIRONMENTS =
            Set.of("dev", "integration", "uat", "prod");

    /**
     * V96 supports GCP and DPC. {@link StorageBackendType} is the canonical
     * enum; the {@link Set} below avoids defensive callsites.
     */
    public static final Set<StorageBackendType> SUPPORTED_BACKENDS =
            EnumSet.of(StorageBackendType.GCP, StorageBackendType.DPC);

    /** Default DPC scheme per V96 seed. */
    public static final String DEFAULT_DPC_SCHEME = "s3a";

    private final TenantService tenantService;

    public StorageRootConventionService(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    /**
     * Derive the conventional V96 defaults for one (tenant, env, backend) cell.
     *
     * @param tenantId tenant id (used to look up the slug — never embedded as-is)
     * @param env      one of {@link #SUPPORTED_ENVIRONMENTS}
     * @param backend  one of {@link #SUPPORTED_BACKENDS}
     * @return a populated {@link ConventionDefaults} record
     * @throws IllegalArgumentException if env / backend is not supported
     *                                  or tenant has no slug configured
     */
    public ConventionDefaults derive(String tenantId, String env, StorageBackendType backend) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must be non-blank");
        }
        if (env == null || env.isBlank()) {
            throw new IllegalArgumentException("env must be non-blank");
        }
        String normalizedEnv = env.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_ENVIRONMENTS.contains(normalizedEnv)) {
            throw new IllegalArgumentException(
                    "Unsupported env: " + env + " (allowed: " + SUPPORTED_ENVIRONMENTS + ")");
        }
        if (backend == null || !SUPPORTED_BACKENDS.contains(backend)) {
            throw new IllegalArgumentException(
                    "Unsupported backend: " + backend + " (allowed: " + SUPPORTED_BACKENDS + ")");
        }

        String slug = resolveSlug(tenantId);
        return switch (backend) {
            case GCP -> gcpDefaults(slug, normalizedEnv);
            case DPC -> dpcDefaults(slug, normalizedEnv);
        };
    }

    private ConventionDefaults gcpDefaults(String slug, String env) {
        return new ConventionDefaults(
                slug,
                env,
                StorageBackendType.GCP,
                "pulse-" + slug + "-" + env + "-files",
                "pulse-" + slug + "-" + env + "-lake",
                "pulse-" + slug + "-" + env, // gcpProject
                null,                          // dpcScheme
                null                           // dpcCluster
        );
    }

    private ConventionDefaults dpcDefaults(String slug, String env) {
        return new ConventionDefaults(
                slug,
                env,
                StorageBackendType.DPC,
                "pulse-dpc-" + slug + "-" + env + "-files",
                "pulse-dpc-" + slug + "-" + env + "-lake",
                null,                              // gcpProject
                DEFAULT_DPC_SCHEME,
                "pulse-dpc-" + slug + "-" + env    // dpcCluster
        );
    }

    private String resolveSlug(String tenantId) {
        try {
            TenantDefinition def = tenantService.getTenant(tenantId);
            String slug = def == null ? null : def.getSlug();
            if (slug == null || slug.isBlank()) {
                throw new IllegalArgumentException(
                        "Tenant " + tenantId + " has no slug; cannot derive convention defaults");
            }
            return slug.toLowerCase(Locale.ROOT);
        } catch (RuntimeException ex) {
            // Bubble up but with a stable message — callers map to 404.
            throw new IllegalArgumentException(
                    "Tenant " + tenantId + " is not known; cannot derive convention defaults", ex);
        }
    }

    /**
     * Output of {@link #derive(String, String, StorageBackendType)}. The
     * {@code files} / {@code lake} fields match the frontend BUG-62
     * destructuring contract ({@code {files, lake}}); the additional
     * fields let the Edit dialog seed every column with one fetch.
     */
    public record ConventionDefaults(
            String tenantSlug,
            String env,
            StorageBackendType backend,
            String files,
            String lake,
            String gcpProject,
            String dpcScheme,
            String dpcCluster
    ) {}
}
