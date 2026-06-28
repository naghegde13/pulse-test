package com.pulse.storage.contract.service;

import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.runtime.service.RuntimeBindingAuthorityFacade;
import com.pulse.runtime.service.RuntimeBindingAuthorityFacade.ResolutionResult;
import com.pulse.runtime.service.RuntimeBindingAuthorityFacade.StorageRoots;
import com.pulse.storage.contract.model.TableContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Projects a logical {@link TableContract} into a concrete, environment-bound
 * {@link TableContractProjection} by resolving storage roots and catalog
 * identifiers from the active runtime binding.
 *
 * <p>This is the boundary between logical table intent (contract) and
 * physical deployment reality (projection). Codegen emits logical contract
 * references; runtime projection resolves them for a specific target
 * environment before packaging and deployment.
 *
 * <p>Projection is deterministic: the same contract + binding + environment
 * produces the same projection hash. Deploy blocks on projection drift.
 */
@Service
public class TableContractProjectionService {

    private static final Logger log = LoggerFactory.getLogger(TableContractProjectionService.class);

    private final RuntimeAuthorityService runtimeAuthorityService;
    private final RuntimeBindingAuthorityFacade bindingAuthorityFacade;

    public TableContractProjectionService(RuntimeAuthorityService runtimeAuthorityService,
                                           RuntimeBindingAuthorityFacade bindingAuthorityFacade) {
        this.runtimeAuthorityService = runtimeAuthorityService;
        this.bindingAuthorityFacade = bindingAuthorityFacade;
    }

    // ------------------------------------------------------------------ projection record

    /**
     * A fully resolved table contract projection for a specific environment.
     *
     * @param resolvedObjectStoreUri    full URI to the table data directory
     *                                  (null for BQ-native warehouse-resident tables)
     * @param resolvedCatalogIdentifier fully qualified catalog identifier
     *                                  (e.g. project.dataset.table for BQ,
     *                                  schema.table for Hive)
     * @param resolvedSchemaOrDataset   resolved schema/dataset name in the catalog
     * @param resolvedRootId            storage root identifier used for resolution
     * @param resolvedRootValue         storage root value used for resolution
     * @param projectionEnvironment     environment this projection was resolved for
     * @param projectionPersona         runtime persona active at projection time
     * @param projectionHash            SHA-256 hash of key projection fields
     *                                  for drift detection
     */
    public record TableContractProjection(
            String resolvedObjectStoreUri,
            String resolvedCatalogIdentifier,
            String resolvedSchemaOrDataset,
            String resolvedRootId,
            String resolvedRootValue,
            String projectionEnvironment,
            String projectionPersona,
            String projectionHash
    ) {}

    // ------------------------------------------------------------------ projection

    /**
     * Project a single table contract for a specific tenant and environment.
     *
     * <p>Resolves storage roots from the active runtime binding and
     * constructs the fully-qualified object store URI and catalog identifier
     * based on the contract's catalog kind.
     *
     * @param contract    the logical table contract
     * @param tenantId    tenant scope for binding resolution
     * @param environment target environment (dev, integration, uat, prod)
     * @return the resolved projection
     * @throws TableContractProjectionException if binding resolution fails
     */
    public TableContractProjection project(TableContract contract,
                                            String tenantId,
                                            String environment) {
        // Resolve storage roots from the active PRIMARY binding
        ResolutionResult<StorageRoots> rootsResult =
                bindingAuthorityFacade.resolveStorageRoots(environment); // PKT-FINAL-5 / BUG-39: global

        if (rootsResult instanceof ResolutionResult.Unresolved<StorageRoots> u) {
            throw new TableContractProjectionException(
                    "Cannot project contract " + contract.getId()
                            + ": " + u.blockerCode() + " — " + u.message());
        }

        StorageRoots roots = ((ResolutionResult.Resolved<StorageRoots>) rootsResult).value();
        String persona = runtimeAuthorityService.getActivePersona().name();

        // Build resolved object store URI: root.lake + "/" + contract.relativeStoragePath
        String resolvedObjectStoreUri = null;
        String lakeRoot = roots.lake();
        if (lakeRoot != null && !lakeRoot.isBlank()
                && contract.getRelativeStoragePath() != null) {
            // Ensure single slash between root and relative path
            String normalizedRoot = lakeRoot.endsWith("/")
                    ? lakeRoot.substring(0, lakeRoot.length() - 1) : lakeRoot;
            resolvedObjectStoreUri = normalizedRoot + "/" + contract.getRelativeStoragePath();
        }

        // Build resolved catalog identifier based on catalog kind
        String resolvedCatalogIdentifier = buildCatalogIdentifier(
                contract.getCatalogKind(),
                contract.getSchemaName(),
                contract.getCatalogTableName(),
                tenantId,
                environment);

        String resolvedSchemaOrDataset = contract.getSchemaName();

        // Compute projection hash for drift detection
        String projectionHash = computeProjectionHash(
                resolvedObjectStoreUri,
                resolvedCatalogIdentifier,
                contract.getTableFormat(),
                contract.getCatalogKind(),
                environment,
                persona);

        log.debug("Projected contract id={} table={} env={} -> uri={}, catalog={}",
                contract.getId(), contract.getTableName(), environment,
                resolvedObjectStoreUri, resolvedCatalogIdentifier);

        return new TableContractProjection(
                resolvedObjectStoreUri,
                resolvedCatalogIdentifier,
                resolvedSchemaOrDataset,
                "storage_root_lake",
                lakeRoot,
                environment,
                persona,
                projectionHash
        );
    }

    /**
     * Project all contracts for a specific tenant and environment.
     */
    public List<TableContractProjection> projectAll(List<TableContract> contracts,
                                                     String tenantId,
                                                     String environment) {
        return contracts.stream()
                .map(c -> project(c, tenantId, environment))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Build a fully-qualified catalog identifier based on the catalog kind.
     *
     * <ul>
     *   <li>BIGQUERY_NATIVE / BIGQUERY_MANAGED_ICEBERG:
     *       {@code {project}.{schemaName}.{catalogTableName}}</li>
     *   <li>HIVE: {@code {schemaName}.{catalogTableName}}</li>
     *   <li>NONE: returns the catalog table name only</li>
     * </ul>
     */
    private String buildCatalogIdentifier(String catalogKind,
                                           String schemaName,
                                           String catalogTableName,
                                           String tenantId,
                                           String environment) {
        if (catalogKind == null) {
            return catalogTableName;
        }
        return switch (catalogKind) {
            case "BIGQUERY_NATIVE", "BIGQUERY_MANAGED_ICEBERG" -> {
                // For BQ, resolve GCP project from binding context
                // Use schema name as dataset identifier
                String project = resolveGcpProject(tenantId, environment);
                yield project + "." + schemaName + "." + catalogTableName;
            }
            case "HIVE" -> schemaName + "." + catalogTableName;
            default -> catalogTableName;
        };
    }

    /**
     * Resolve GCP project from the runtime binding for BQ catalog identifiers.
     * Falls back to a placeholder when unavailable.
     */
    private String resolveGcpProject(String tenantId, String environment) {
        // Runtime authority for GCP persona exposes project via binding
        // The binding's storage root typically encodes the project
        ResolutionResult<StorageRoots> rootsResult =
                bindingAuthorityFacade.resolveStorageRoots(environment); // PKT-FINAL-5 / BUG-39: global
        if (rootsResult instanceof ResolutionResult.Resolved<StorageRoots> r) {
            String lakeRoot = r.value().lake();
            if (lakeRoot != null && lakeRoot.startsWith("gs://")) {
                // Extract bucket as project hint — actual project resolution
                // happens via deployment target; placeholder for preview
                return "${gcp_project}";
            }
        }
        return "${gcp_project}";
    }

    /**
     * Compute a SHA-256 hash of key projection fields for drift detection.
     */
    private String computeProjectionHash(String objectStoreUri,
                                          String catalogIdentifier,
                                          String tableFormat,
                                          String catalogKind,
                                          String environment,
                                          String persona) {
        String payload = String.join("|",
                objectStoreUri != null ? objectStoreUri : "",
                catalogIdentifier != null ? catalogIdentifier : "",
                tableFormat != null ? tableFormat : "",
                catalogKind != null ? catalogKind : "",
                environment != null ? environment : "",
                persona != null ? persona : "");
        return sha256(payload);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ------------------------------------------------------------------ exception

    public static class TableContractProjectionException extends RuntimeException {
        public TableContractProjectionException(String message) {
            super(message);
        }
    }
}
