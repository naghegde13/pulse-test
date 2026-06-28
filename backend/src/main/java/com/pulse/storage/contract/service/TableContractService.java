package com.pulse.storage.contract.service;

import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.storage.contract.model.TableContract;
import com.pulse.storage.contract.repository.TableContractRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Derives and manages {@link TableContract} records for materializing
 * sub-pipeline instances within a pipeline version.
 *
 * <p>A table contract is the single authority for a materialised table's
 * physical name, layer, storage path, catalog placement, and format.
 * Codegen, packaging, deploy, and chat all consume contracts — none of
 * them construct table names or paths independently.
 *
 * <p>Contract generation is deterministic: given the same composition
 * inputs, the same contracts are produced. Previous active contracts for
 * the same (versionId, producingInstanceId, outputPortName) are marked
 * stale before new ones are persisted.
 */
@Service
public class TableContractService {

    private static final Logger log = LoggerFactory.getLogger(TableContractService.class);

    private final TableContractRepository tableContractRepository;
    private final RuntimeAuthorityService runtimeAuthorityService;

    public TableContractService(TableContractRepository tableContractRepository,
                                RuntimeAuthorityService runtimeAuthorityService) {
        this.tableContractRepository = tableContractRepository;
        this.runtimeAuthorityService = runtimeAuthorityService;
    }

    // ------------------------------------------------------------------ generation

    /**
     * Generate table contracts for all materializing instances in a version.
     *
     * <p>For each instance that has a non-null {@code lakeLayer}, derives
     * a table contract with layer-appropriate naming conventions:
     * <ul>
     *   <li>Bronze: {@code {sourceSorSlug}_{datasetOrTableSlug}},
     *       path = {@code {domainSlug}/bronze/{sourceSorSlug}/{tableName}/}</li>
     *   <li>Silver: {@code {tableSlug}},
     *       path = {@code {domainSlug}/silver/{tableName}/}</li>
     *   <li>Gold: {@code {tableSlug}},
     *       path = {@code {domainSlug}/gold/{tableName}/}</li>
     * </ul>
     *
     * @param pipelineId  pipeline that owns the version
     * @param versionId   version being contracted
     * @param instances   sub-pipeline instances from the composition
     * @param wirings     composition wiring map (reserved for future port analysis)
     * @param domainId    domain that owns this pipeline
     * @param domainSlug  path-safe domain slug
     * @return generated and persisted contracts
     */
    @Transactional
    public List<TableContract> generateContracts(String pipelineId,
                                                  String versionId,
                                                  List<SubPipelineInstance> instances,
                                                  List<Map<String, Object>> wirings,
                                                  String domainId,
                                                  String domainSlug) {
        List<TableContract> generated = new ArrayList<>();

        for (SubPipelineInstance instance : instances) {
            String layer = resolveLayer(instance);
            if (layer == null) {
                // Non-materializing instance (sensor, policy, orchestration-only)
                continue;
            }

            // Mark stale any previous active contracts for this instance
            markStale(versionId, instance.getId());

            String tableSlug = slugify(instance.getName());
            String tableName;
            String relativeStoragePath;
            String schemaName;
            String tableRole;
            String sourceSorSlug = null;
            String sourceDatasetSlug = null;
            String sourceSorId = null;
            String sourceDatasetId = null;

            switch (layer) {
                case "bronze" -> {
                    // Bronze retains source identity in name and path
                    sourceSorSlug = resolveBronzeSourceSorSlug(instance);
                    sourceDatasetSlug = resolveBronzeSourceDatasetSlug(instance);
                    sourceSorId = resolveBronzeSourceSorId(instance);
                    sourceDatasetId = resolveBronzeSourceDatasetId(instance);
                    String datasetOrTableSlug = sourceDatasetSlug != null
                            ? sourceDatasetSlug : tableSlug;
                    String sorPrefix = sourceSorSlug != null ? sourceSorSlug : "unknown";
                    tableName = slugify(sorPrefix + "_" + datasetOrTableSlug);
                    relativeStoragePath = domainSlug + "/bronze/" + sorPrefix + "/" + tableName + "/";
                    schemaName = domainSlug + "_bronze";
                    tableRole = "bronze_raw";
                }
                case "silver" -> {
                    tableName = tableSlug;
                    relativeStoragePath = domainSlug + "/silver/" + tableName + "/";
                    schemaName = domainSlug + "_silver";
                    tableRole = "silver_curated";
                }
                case "gold" -> {
                    tableName = tableSlug;
                    relativeStoragePath = domainSlug + "/gold/" + tableName + "/";
                    schemaName = domainSlug + "_gold";
                    tableRole = "gold_serving";
                }
                default -> {
                    log.warn("Unknown lake layer '{}' for instance {}; skipping contract",
                            layer, instance.getId());
                    continue;
                }
            }

            // Derive catalog kind from runtime authority
            String catalogKind = deriveCatalogKind(layer);

            // Build catalog table name (snake_case for BQ)
            String catalogTableName = slugify(tableName);

            TableContract contract = new TableContract();
            contract.setPipelineId(pipelineId);
            contract.setVersionId(versionId);
            contract.setProducingInstanceId(instance.getId());
            contract.setOutputPortName("main_output");
            contract.setDomainId(domainId);
            contract.setDomainSlug(domainSlug);
            contract.setLayer(layer);
            contract.setTableRole(tableRole);
            contract.setTableName(tableName);
            contract.setTableSlug(tableSlug);
            contract.setLogicalTableId(versionId + "/" + instance.getId() + "/main_output");
            contract.setSourceSorId(sourceSorId);
            contract.setSourceSorSlug(sourceSorSlug);
            contract.setSourceDatasetId(sourceDatasetId);
            contract.setSourceDatasetSlug(sourceDatasetSlug);
            contract.setTableFormat(instance.getLakeFormat() != null
                    ? instance.getLakeFormat() : "parquet");
            contract.setCatalogKind(catalogKind);
            contract.setSchemaName(schemaName);
            contract.setCatalogTableName(catalogTableName);
            contract.setRelativeStoragePath(relativeStoragePath);
            contract.setWriteMode("append");
            contract.setDdlStrategy("create_if_not_exists");
            contract.setWriterOwner(instance.getId());
            contract.setContractVersion(1);
            contract.setStatus("active");
            contract.setProvenance(Map.of(
                    "source", "contract_generation",
                    "blueprintKey", instance.getBlueprintKey() != null
                            ? instance.getBlueprintKey() : "unknown",
                    "instanceName", instance.getName()
            ));

            TableContract saved = tableContractRepository.save(contract);
            generated.add(saved);
            log.debug("Generated table contract id={} table={} layer={} for instance={}",
                    saved.getId(), tableName, layer, instance.getId());
        }

        log.info("Generated {} table contracts for version={}", generated.size(), versionId);
        return generated;
    }

    // ------------------------------------------------------------------ queries

    /**
     * Find all active table contracts for a pipeline version.
     */
    public List<TableContract> findActiveContracts(String versionId) {
        return tableContractRepository.findByVersionIdAndStatus(versionId, "active");
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Mark all active contracts for a specific producing instance as stale.
     * Called before regeneration and when composition changes invalidate
     * existing contracts.
     */
    @Transactional
    public void markStale(String versionId, String producingInstanceId) {
        List<TableContract> active = tableContractRepository
                .findByVersionIdAndProducingInstanceIdAndStatus(versionId, producingInstanceId, "active");
        for (TableContract contract : active) {
            contract.setStatus("stale");
            tableContractRepository.save(contract);
            log.debug("Marked contract id={} as stale for instance={}",
                    contract.getId(), producingInstanceId);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Slugify a name: lowercase, replace non-alphanumeric chars with underscore,
     * collapse runs, trim leading/trailing underscores.
     */
    static String slugify(String name) {
        if (name == null || name.isBlank()) {
            return "unnamed";
        }
        String lower = name.toLowerCase();
        StringBuilder sb = new StringBuilder(lower.length());
        boolean prevUnderscore = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                prevUnderscore = false;
            } else {
                if (!prevUnderscore && sb.length() > 0) {
                    sb.append('_');
                    prevUnderscore = true;
                }
            }
        }
        // Trim trailing underscore
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.length() == 0 ? "unnamed" : sb.toString();
    }

    /**
     * Resolve the medallion layer from an instance's lakeLayer column.
     * Returns null for non-materializing instances.
     */
    static String resolveLayer(SubPipelineInstance instance) {
        String lakeLayer = instance.getLakeLayer();
        if (lakeLayer == null || lakeLayer.isBlank()) {
            return null;
        }
        return lakeLayer.toLowerCase();
    }

    /**
     * Derive the catalog kind from the runtime authority and the target layer.
     * GCP gold uses BQ native; GCP bronze/silver use BQ-managed Iceberg;
     * DPC uses Hive.
     */
    private String deriveCatalogKind(String layer) {
        var authority = runtimeAuthorityService.getAuthority();
        if (authority.allowedCatalogs().contains("BIGQUERY")) {
            if ("gold".equals(layer)) {
                return "BIGQUERY_NATIVE";
            }
            return "BIGQUERY_MANAGED_ICEBERG";
        }
        if (authority.allowedCatalogs().contains("HIVE_JDBC")) {
            return "HIVE";
        }
        // Default fallback — should not happen with valid runtime authority
        return "NONE";
    }

    // ------------------------------------------------------------------ bronze source resolution

    private String resolveBronzeSourceSorSlug(SubPipelineInstance instance) {
        List<Map<String, Object>> inputs = instance.getInputDatasets();
        if (inputs != null && !inputs.isEmpty()) {
            Object slug = inputs.get(0).get("sorSlug");
            if (slug != null) return slugify(slug.toString());
        }
        return null;
    }

    private String resolveBronzeSourceDatasetSlug(SubPipelineInstance instance) {
        List<Map<String, Object>> inputs = instance.getInputDatasets();
        if (inputs != null && !inputs.isEmpty()) {
            Object slug = inputs.get(0).get("datasetSlug");
            if (slug == null) slug = inputs.get(0).get("qualifiedName");
            if (slug != null) return slugify(slug.toString());
        }
        return null;
    }

    private String resolveBronzeSourceSorId(SubPipelineInstance instance) {
        List<Map<String, Object>> inputs = instance.getInputDatasets();
        if (inputs != null && !inputs.isEmpty()) {
            Object id = inputs.get(0).get("sorId");
            if (id != null) return id.toString();
        }
        return null;
    }

    private String resolveBronzeSourceDatasetId(SubPipelineInstance instance) {
        List<Map<String, Object>> inputs = instance.getInputDatasets();
        if (inputs != null && !inputs.isEmpty()) {
            Object id = inputs.get(0).get("datasetId");
            if (id == null) id = inputs.get(0).get("id");
            if (id != null) return id.toString();
        }
        return null;
    }
}
