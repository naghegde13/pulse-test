package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.codegen.model.GeneratedArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparkDbtGxRuntimeCollectorsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void collect_marksMissingForApplicableSubsystemsAndNotApplicableForAbsentOnes() throws Exception {
        SparkDbtGxRuntimeCollectors collectors = new SparkDbtGxRuntimeCollectors(objectMapper);

        GeneratedArtifact spark = artifact("jobs/ingestion/loan_master_ingest.py", "PYSPARK_JOB");
        GeneratedArtifact dbtModel = artifact("dbt_project/models/intermediate/servicing/int__servicing__filter_current_loans.sql", "DBT_MODEL");
        GeneratedArtifact dbtSource = artifact("dbt_project/models/staging/loan_drops/_loan_drops__sources.yml", "DBT_SOURCE");
        GeneratedArtifact selector = artifact("dbt_project/selectors/loan_master.yml", "DBT_SELECTOR");

        SparkDbtGxRuntimeCollectors.CollectorBundle bundle = collectors.collect(
                new SparkDbtGxRuntimeCollectors.CollectorRequest(
                        "loan-master-live-runtime",
                        "run-1",
                        List.of(spark, dbtModel, dbtSource, selector),
                        List.of(),
                        tempDir.resolve("evidence")
                )
        );

        assertEquals("APPLICABLE", bundle.spark().applicability());
        assertEquals("MISSING", bundle.spark().verdict());
        assertTrue(bundle.spark().failureCodes().contains("missing_spark_runtime_evidence"));

        assertEquals("APPLICABLE", bundle.dbt().applicability());
        assertEquals("MISSING", bundle.dbt().verdict());
        assertTrue(bundle.dbt().failureCodes().contains("missing_dbt_parse_evidence"));
        assertTrue(bundle.dbt().failureCodes().contains("missing_dbt_run_evidence"));

        assertEquals("NOT_APPLICABLE", bundle.gx().applicability());
        assertEquals("NOT_APPLICABLE", bundle.gx().verdict());
        assertTrue(bundle.gx().observedEvidence().isEmpty());

        assertTrue(Files.exists(tempDir.resolve("evidence/spark-runtime-collection.json")));
        assertTrue(Files.exists(tempDir.resolve("evidence/dbt-runtime-collection.json")));
        assertTrue(Files.exists(tempDir.resolve("evidence/gx-runtime-collection.json")));
    }

    @Test
    void collect_recordsSuccessfulEvidenceAndHashesLogs() throws Exception {
        SparkDbtGxRuntimeCollectors collectors = new SparkDbtGxRuntimeCollectors(objectMapper);

        GeneratedArtifact spark = artifact("jobs/ingestion/loan_master_ingest.py", "PYSPARK_JOB");
        GeneratedArtifact dbtModel = artifact("dbt_project/models/intermediate/servicing/int__servicing__filter_current_loans.sql", "DBT_MODEL");
        GeneratedArtifact gx = artifact("gx/checkpoints/filter_current_loans_checkpoint.py", "GX_CHECKPOINT");

        Path sparkLog = Files.writeString(tempDir.resolve("spark.log"), "spark finished successfully\n");
        Path dbtParseLog = Files.writeString(tempDir.resolve("dbt-parse.log"), "dbt parse ok\n");
        Path dbtRunLog = Files.writeString(tempDir.resolve("dbt-run.log"), "dbt run ok\n");
        Path gxLog = Files.writeString(tempDir.resolve("gx.log"), "gx checkpoint ok\n");

        List<SparkDbtGxRuntimeCollectors.RuntimeEvidence> evidence = List.of(
                new SparkDbtGxRuntimeCollectors.RuntimeEvidence(
                        "spark-task", "AIRFLOW_TASK", "run", "ingest_loan_master", "SparkSubmitOperator",
                        "python jobs/ingestion/loan_master_ingest.py", null, "success", 0, sparkLog, Map.of()),
                new SparkDbtGxRuntimeCollectors.RuntimeEvidence(
                        "dbt-parse", "DBT_PARSE", "parse", "filter_current_loans", "BashOperator",
                        "dbt parse --selector loan_master", null, "success", 0, dbtParseLog, Map.of()),
                new SparkDbtGxRuntimeCollectors.RuntimeEvidence(
                        "dbt-run", "DBT_RUN", "run", "filter_current_loans", "BashOperator",
                        "dbt run --selector loan_master", null, "success", 0, dbtRunLog, Map.of()),
                new SparkDbtGxRuntimeCollectors.RuntimeEvidence(
                        "gx-checkpoint", "GX_CHECKPOINT", "run", "gx_bronze_silver_gate", "PythonOperator",
                        "python gx/checkpoints/filter_current_loans_checkpoint.py", "gx/checkpoints/filter_current_loans_checkpoint.py", "success", 0, gxLog, Map.of())
        );

        SparkDbtGxRuntimeCollectors.CollectorBundle bundle = collectors.collect(
                new SparkDbtGxRuntimeCollectors.CollectorRequest(
                        "loan-master-live-runtime",
                        "run-2",
                        List.of(spark, dbtModel, gx),
                        evidence,
                        tempDir.resolve("evidence-pass")
                )
        );

        assertEquals("PASS", bundle.spark().verdict());
        assertEquals("PASS", bundle.dbt().verdict());
        assertEquals("PASS", bundle.gx().verdict());
        assertFalse(bundle.spark().observedEvidence().isEmpty());
        assertFalse(bundle.dbt().observedEvidence().isEmpty());
        assertFalse(bundle.gx().observedEvidence().isEmpty());
        assertNotNull(bundle.spark().observedEvidence().getFirst().logSha256());
        assertNotNull(bundle.dbt().observedEvidence().getFirst().logSha256());
        assertNotNull(bundle.gx().observedEvidence().getFirst().logSha256());
    }

    private GeneratedArtifact artifact(String path, String type) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setFilePath(path);
        artifact.setFileType(type);
        artifact.setContent("test");
        return artifact;
    }
}
