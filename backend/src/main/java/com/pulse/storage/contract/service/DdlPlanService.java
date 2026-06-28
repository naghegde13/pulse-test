package com.pulse.storage.contract.service;

import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.storage.contract.model.TableContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Generates executable DDL plans from table contracts and catalog bindings.
 *
 * <p>DDL plans are the deployment authority for database/schema/table
 * creation. Each {@link DdlStatement} has an executor, dialect, body,
 * and SHA-256 hash. Deploy adapters consume DDL plans — they do not
 * construct DDL independently.
 *
 * <p>Supported catalog kinds:
 * <ul>
 *   <li>HIVE: Hive DDL via {@code HIVE_JDBC} executor</li>
 *   <li>BIGQUERY_NATIVE: BigQuery DDL via {@code BIGQUERY_SQL} executor</li>
 *   <li>BIGQUERY_MANAGED_ICEBERG: BigLake Iceberg DDL via
 *       {@code BIGQUERY_SQL} executor</li>
 * </ul>
 */
@Service
public class DdlPlanService {

    private static final Logger log = LoggerFactory.getLogger(DdlPlanService.class);

    private final RuntimeAuthorityService runtimeAuthorityService;

    private int statementCounter = 0;

    public DdlPlanService(RuntimeAuthorityService runtimeAuthorityService) {
        this.runtimeAuthorityService = runtimeAuthorityService;
    }

    // ------------------------------------------------------------------ DDL statement record

    /**
     * A single executable DDL statement within a plan.
     *
     * @param statementId     unique statement identifier
     * @param phase           execution phase (schema, table)
     * @param executor        execution target (HIVE_JDBC, BIGQUERY_SQL, SPARK_SQL)
     * @param dialect         SQL dialect (HIVE, BIGQUERY, SPARK_ICEBERG)
     * @param body            the DDL statement body
     * @param sha256          SHA-256 hash of the body for idempotency checks
     * @param tableContractId the table contract this statement supports
     * @param idempotencyMode idempotency guarantee (create_if_not_exists,
     *                        create_or_replace)
     */
    public record DdlStatement(
            String statementId,
            String phase,
            String executor,
            String dialect,
            String body,
            String sha256,
            String tableContractId,
            String idempotencyMode
    ) {}

    // ------------------------------------------------------------------ plan generation

    /**
     * Generate an ordered DDL plan for the given table contracts.
     *
     * <p>Schema-creation statements come first, followed by table-creation
     * statements. Each statement is tagged with the originating contract ID.
     *
     * @param contracts       table contracts to generate DDL for
     * @param persona         active runtime persona (for logging/validation)
     * @param catalogBindings catalog binding context (reserved for future use)
     * @return ordered list of DDL statements
     */
    public List<DdlStatement> generateDdlPlan(List<TableContract> contracts,
                                               String persona,
                                               Map<String, Object> catalogBindings) {
        List<DdlStatement> plan = new ArrayList<>();
        List<String> emittedSchemas = new ArrayList<>();

        for (TableContract contract : contracts) {
            String catalogKind = contract.getCatalogKind();
            if (catalogKind == null || "NONE".equals(catalogKind)) {
                log.debug("Skipping DDL for contract id={} with catalogKind={}",
                        contract.getId(), catalogKind);
                continue;
            }

            switch (catalogKind) {
                case "HIVE" -> generateHiveDdl(contract, plan, emittedSchemas);
                case "BIGQUERY_NATIVE" -> generateBigQueryNativeDdl(contract, plan, emittedSchemas);
                case "BIGQUERY_MANAGED_ICEBERG" -> generateBigLakeIcebergDdl(contract, plan, emittedSchemas);
                default -> log.warn("Unknown catalogKind '{}' for contract id={}; skipping DDL",
                        catalogKind, contract.getId());
            }
        }

        log.info("Generated DDL plan with {} statements for {} contracts",
                plan.size(), contracts.size());
        return plan;
    }

    // ------------------------------------------------------------------ Hive DDL

    private void generateHiveDdl(TableContract contract,
                                  List<DdlStatement> plan,
                                  List<String> emittedSchemas) {
        String schemaName = contract.getSchemaName();

        // Schema creation (deduplicated)
        if (!emittedSchemas.contains(schemaName)) {
            String createDb = "CREATE DATABASE IF NOT EXISTS " + schemaName + ";";
            plan.add(buildStatement("schema", "HIVE_JDBC", "HIVE",
                    createDb, contract.getId(), "create_if_not_exists"));
            emittedSchemas.add(schemaName);
        }

        // Table creation
        String tableBody = buildHiveCreateTable(contract);
        plan.add(buildStatement("table", "HIVE_JDBC", "HIVE",
                tableBody, contract.getId(), "create_if_not_exists"));
    }

    private String buildHiveCreateTable(TableContract contract) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE EXTERNAL TABLE IF NOT EXISTS ");
        sb.append(contract.getSchemaName()).append(".").append(contract.getCatalogTableName());
        sb.append(" (\n");
        sb.append("  -- columns derived from output schema at projection time\n");
        sb.append("  _placeholder STRING\n");
        sb.append(")\n");

        // Partition clause if contract has partition spec
        if (contract.getPartitionSpec() != null && !contract.getPartitionSpec().isEmpty()) {
            Object fields = contract.getPartitionSpec().get("fields");
            if (fields instanceof List<?> fieldList && !fieldList.isEmpty()) {
                sb.append("PARTITIONED BY (\n");
                for (int i = 0; i < fieldList.size(); i++) {
                    if (fieldList.get(i) instanceof Map<?, ?> field) {
                        String colName = field.get("name") != null
                                ? field.get("name").toString() : "partition_col";
                        String colType = field.get("type") != null
                                ? field.get("type").toString() : "STRING";
                        sb.append("  ").append(colName).append(" ").append(colType.toUpperCase());
                        if (i < fieldList.size() - 1) sb.append(",");
                        sb.append("\n");
                    }
                }
                sb.append(")\n");
            }
        }

        sb.append("STORED AS PARQUET\n");
        sb.append("LOCATION '${resolved_storage_path}");
        sb.append(contract.getRelativeStoragePath()).append("';");
        return sb.toString();
    }

    // ------------------------------------------------------------------ BigQuery Native DDL

    private void generateBigQueryNativeDdl(TableContract contract,
                                            List<DdlStatement> plan,
                                            List<String> emittedSchemas) {
        String schemaName = contract.getSchemaName();

        // Dataset creation (deduplicated)
        if (!emittedSchemas.contains(schemaName)) {
            String createDataset = "CREATE SCHEMA IF NOT EXISTS "
                    + "${gcp_project}." + schemaName + ";";
            plan.add(buildStatement("schema", "BIGQUERY_SQL", "BIGQUERY",
                    createDataset, contract.getId(), "create_if_not_exists"));
            emittedSchemas.add(schemaName);
        }

        // Table creation
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ");
        sb.append("${gcp_project}.").append(schemaName).append(".")
                .append(contract.getCatalogTableName());
        sb.append(" (\n");
        sb.append("  -- columns derived from output schema at projection time\n");
        sb.append("  _placeholder STRING\n");
        sb.append(")");

        // Partition clause for BQ native
        if (contract.getPartitionSpec() != null && !contract.getPartitionSpec().isEmpty()) {
            Object fields = contract.getPartitionSpec().get("fields");
            if (fields instanceof List<?> fieldList && !fieldList.isEmpty()) {
                if (fieldList.get(0) instanceof Map<?, ?> field) {
                    String colName = field.get("name") != null
                            ? field.get("name").toString() : "event_date";
                    sb.append("\nPARTITION BY ").append(colName);
                }
            }
        }

        // Clustering
        if (contract.getLayoutSpec() != null) {
            Object clusterCols = contract.getLayoutSpec().get("clusterColumns");
            if (clusterCols instanceof List<?> cols && !cols.isEmpty()) {
                sb.append("\nCLUSTER BY ").append(String.join(", ",
                        cols.stream().map(Object::toString).toList()));
            }
        }

        sb.append(";");
        plan.add(buildStatement("table", "BIGQUERY_SQL", "BIGQUERY",
                sb.toString(), contract.getId(), "create_if_not_exists"));
    }

    // ------------------------------------------------------------------ BigLake Iceberg DDL

    private void generateBigLakeIcebergDdl(TableContract contract,
                                            List<DdlStatement> plan,
                                            List<String> emittedSchemas) {
        String schemaName = contract.getSchemaName();

        // Dataset creation (deduplicated)
        if (!emittedSchemas.contains(schemaName)) {
            String createDataset = "CREATE SCHEMA IF NOT EXISTS "
                    + "${gcp_project}." + schemaName + ";";
            plan.add(buildStatement("schema", "BIGQUERY_SQL", "BIGQUERY",
                    createDataset, contract.getId(), "create_if_not_exists"));
            emittedSchemas.add(schemaName);
        }

        // BigLake Iceberg managed table
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ");
        sb.append("${gcp_project}.").append(schemaName).append(".")
                .append(contract.getCatalogTableName());
        sb.append(" (\n");
        sb.append("  -- columns derived from output schema at projection time\n");
        sb.append("  _placeholder STRING\n");
        sb.append(")\n");
        sb.append("WITH CONNECTION `${biglake_connection}`\n");
        sb.append("OPTIONS (\n");
        sb.append("  file_format = 'PARQUET',\n");
        sb.append("  table_format = 'ICEBERG',\n");
        sb.append("  storage_uri = '${resolved_storage_path}")
                .append(contract.getRelativeStoragePath()).append("'\n");
        sb.append(");");

        plan.add(buildStatement("table", "BIGQUERY_SQL", "BIGQUERY",
                sb.toString(), contract.getId(), "create_if_not_exists"));
    }

    // ------------------------------------------------------------------ helpers

    private DdlStatement buildStatement(String phase,
                                         String executor,
                                         String dialect,
                                         String body,
                                         String tableContractId,
                                         String idempotencyMode) {
        String id = "ddl_" + (++statementCounter);
        return new DdlStatement(
                id,
                phase,
                executor,
                dialect,
                body,
                sha256(body),
                tableContractId,
                idempotencyMode
        );
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
}
