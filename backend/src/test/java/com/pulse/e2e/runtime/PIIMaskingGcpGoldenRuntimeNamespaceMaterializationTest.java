package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PIIMaskingGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0830";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String INPUT_ROOT = "gs://pulse-home-lending-dev-files/semantic/pii-masking/input";
    private static final String CONFIG_URI = INPUT_ROOT + "/expectations/masking-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/pii-masking/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("pii-masking")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializePIIMaskingGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/transform/pii_masking.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/pii_masking_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, maskingJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written =
                new GcpComposerDataprocBridgeAdapter(objectMapper).writeEvidence(request(), EVIDENCE_ROOT);

        assertTrue(Files.exists(mainPython));
        assertTrue(Files.exists(secretResolver));
        assertTrue(Files.exists(dag));
        assertTrue(Files.readString(mainPython).contains("masked-employees"));
        assertTrue(Files.readString(mainPython).contains("rawLeakCount"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_pii_masking"));
        assertTrue(renderedDag.contains("semantic-pii-masking-batch-20260506-0830"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("pii-masking-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("semantic-pii-masking-batch-20260506-0830", submit.get("batchId").asText());
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "pii-masking-gcp-golden", RUN_ID, "pulse-proof-04261847", "us-central1",
                "pulse-proof-composer", "gs://us-central1-pulse-proof-com-a7066110-bucket/dags", "us-central1",
                DATAPROC_RUNTIME_SERVICE_ACCOUNT, STAGING_BUCKET, OUTPUT_PREFIX, NAMESPACE,
                "pulse_semantic_gcp_pii_masking", "pii_masking_gcp_dag.py",
                "pulse_semantic__pii_masking_run_20260506_0830", "2026-05-06T08:30:00+00:00",
                "semantic-pii-masking-batch-20260506-0830", "jobs/transform/pii_masking.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of("--positive-input", INPUT_ROOT + "/positive/employees_pii.csv",
                        "--edge-input", INPUT_ROOT + "/edge/employees_edge.csv",
                        "--negative-input", INPUT_ROOT + "/negative/employees_leak_attempt.csv",
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX),
                "GCLOUD_SUBPROCESS", "GCLOUD_COMPOSER_RUN", "aamer@aamer.net", RUNTIME_ROOT
        );
    }

    private String maskingJob() {
        return """
                from __future__ import annotations
                import argparse, json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F

                def parse_args():
                    p = argparse.ArgumentParser(description="PIIMasking semantic GCP golden proof job")
                    p.add_argument("--positive-input", required=True)
                    p.add_argument("--edge-input", required=True)
                    p.add_argument("--negative-input", required=True)
                    p.add_argument("--config", required=True)
                    p.add_argument("--output", required=True)
                    return p.parse_args()

                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))

                def read_employees(spark, uri):
                    return spark.read.option("header", "true").csv(uri)

                def masked(column_name, prefix):
                    c = F.col(column_name)
                    return F.when(c.isNull(), F.lit(None)) \\
                        .when(c.startswith("MASKED_"), c) \\
                        .otherwise(F.concat(F.lit(prefix), F.substring(F.sha2(c, 256), 1, 12)))

                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-pii-masking-gcp-golden").getOrCreate()
                    try:
                        config = read_json_config(spark, args.config)
                        employees = read_employees(spark, args.positive_input).unionByName(read_employees(spark, args.edge_input))
                        negative = read_employees(spark, args.negative_input)
                        phone_digits = F.regexp_replace(F.col("phone"), "[^0-9]", "")
                        invalid_email = F.col("email").isNotNull() & (~F.col("email").startswith("MASKED_")) & (~F.col("email").contains("@"))
                        invalid_phone = F.col("phone").isNotNull() & (~F.col("phone").startswith("MASKED_")) & (F.length(phone_digits) != 10)
                        rejects = negative.where(invalid_email | invalid_phone).select("employee_id", F.lit("invalid-pii-format").alias("reason")).orderBy("employee_id")
                        valid = employees.withColumn("phone_digits", F.regexp_replace(F.col("phone"), "[^0-9]", ""))
                        masked_employees = valid.select(
                            "employee_id",
                            masked("ssn", "MASKED_SSN_").alias("ssn_masked"),
                            masked("email", "MASKED_EMAIL_").alias("email_masked"),
                            F.when(F.col("phone").isNull(), F.lit(None))
                                .when(F.col("phone").startswith("MASKED_"), F.col("phone"))
                                .otherwise(F.concat(F.lit("MASKED_PHONE_"), F.substring(F.sha2(F.col("phone_digits"), 256), 1, 12))).alias("phone_masked"),
                            "salary", "dob", "department"
                        ).orderBy("employee_id")
                        output_rows = [str(r.asDict(recursive=True)) for r in masked_employees.collect()]
                        reject_rows = [str(r.asDict(recursive=True)) for r in rejects.collect()]
                        combined_output = "\\n".join(output_rows + reject_rows)
                        leaked_terms = [term for term in config.get("leakScanTerms", []) if term and term in combined_output]
                        raw_leak_count = len(leaked_terms)
                        masked_employees.coalesce(1).write.mode("overwrite").option("header", "true").csv(args.output + "/masked-employees")
                        rejects.coalesce(1).write.mode("overwrite").option("header", "true").csv(args.output + "/pii-rejects")
                        reject_ids = [r["employee_id"] for r in rejects.collect()]
                        report = {"blueprintKey":"PIIMasking","maskedRowCount":masked_employees.count(),"rejectEmployeeIds":reject_ids,"rawLeakCount":raw_leak_count,"verdict":"draft-pass"}
                        metrics = {"maskedRowCount":masked_employees.count(),"rejectRowCount":rejects.count(),"rawLeakCount":raw_leak_count}
                        leak_scan = {"rawLeakCount":raw_leak_count,"leakedTerms":leaked_terms}
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(args.output + "/pii-report")
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(args.output + "/pii-metrics")
                        spark.sparkContext.parallelize([json.dumps(leak_scan, sort_keys=True)]).saveAsTextFile(args.output + "/leak-scan")
                    finally:
                        spark.stop()

                if __name__ == "__main__":
                    main()
                """;
    }

    private String secretResolverModule() {
        return """
                from __future__ import annotations
                def resolve_runtime_secret_env(secret_bindings=None):
                    return {}, []
                def cleanup_runtime_secret_files(runtime_files=None):
                    return None
                """;
    }
}
