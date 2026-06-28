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

class SchemaNormalizationGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0625";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String INPUT_ROOT = "gs://pulse-home-lending-dev-files/semantic/schema-normalization/input";
    private static final String CONFIG_URI = INPUT_ROOT + "/expectations/normalization-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/schema-normalization/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("schema-normalization")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeSchemaNormalizationGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/transform/schema_normalization.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/schema_normalization_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, schemaNormalizationJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter adapter = new GcpComposerDataprocBridgeAdapter(objectMapper);
        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written = adapter.writeEvidence(request(), EVIDENCE_ROOT);

        assertTrue(Files.exists(mainPython));
        assertTrue(Files.exists(secretResolver));
        assertTrue(Files.exists(dag));

        String job = Files.readString(mainPython);
        assertTrue(job.contains("canonical-suppliers"));
        assertTrue(job.contains("normalization-rejects"));
        assertTrue(job.contains("badCastSupplierIds"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_schema_normalization"));
        assertTrue(renderedDag.contains("semantic-schema-normalization-batch-20260506-0625"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("schema-normalization-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("semantic-schema-normalization-batch-20260506-0625", submit.get("batchId").asText());
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "schema-normalization-gcp-golden",
                RUN_ID,
                "pulse-proof-04261847",
                "us-central1",
                "pulse-proof-composer",
                "gs://us-central1-pulse-proof-com-a7066110-bucket/dags",
                "us-central1",
                DATAPROC_RUNTIME_SERVICE_ACCOUNT,
                STAGING_BUCKET,
                OUTPUT_PREFIX,
                NAMESPACE,
                "pulse_semantic_gcp_schema_normalization",
                "schema_normalization_gcp_dag.py",
                "pulse_semantic__schema_normalization_run_20260506_0625",
                "2026-05-06T06:25:00+00:00",
                "semantic-schema-normalization-batch-20260506-0625",
                "jobs/transform/schema_normalization.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of(
                        "--positive-input", INPUT_ROOT + "/positive/supplier_raw.csv",
                        "--edge-input", INPUT_ROOT + "/edge/supplier_whitespace_case.csv",
                        "--negative-input", INPUT_ROOT + "/negative/supplier_bad_casts.csv",
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX
                ),
                "GCLOUD_SUBPROCESS",
                "GCLOUD_COMPOSER_RUN",
                "aamer@aamer.net",
                RUNTIME_ROOT
        );
    }

    private String schemaNormalizationJob() {
        return """
                from __future__ import annotations

                import argparse
                import json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F


                def parse_args():
                    parser = argparse.ArgumentParser(description="SchemaNormalization semantic GCP golden proof job")
                    parser.add_argument("--positive-input", required=True)
                    parser.add_argument("--edge-input", required=True)
                    parser.add_argument("--negative-input", required=True)
                    parser.add_argument("--config", required=True)
                    parser.add_argument("--output", required=True)
                    return parser.parse_args()


                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))


                def normalize(df):
                    raw = df.select(
                        F.col("Supplier ID").alias("supplier_id"),
                        F.trim(F.col(" Supplier Name ")).alias("supplier_name"),
                        F.upper(F.trim(F.col("Country Code"))).alias("country_code"),
                        F.col("Onboard Date").alias("raw_onboard_date"),
                        F.col("Payment Terms Days").alias("raw_payment_terms_days"),
                        F.lower(F.trim(F.col("Active Flag"))).alias("raw_active_flag"),
                    )
                    return raw.select(
                        "supplier_id",
                        "supplier_name",
                        "country_code",
                        F.coalesce(
                            F.to_date("raw_onboard_date", "yyyy-MM-dd"),
                            F.to_date("raw_onboard_date", "MM/dd/yyyy"),
                            F.to_date("raw_onboard_date", "dd-MMM-yyyy"),
                            F.to_date("raw_onboard_date", "yyyy/MM/dd"),
                        ).alias("onboard_date"),
                        F.col("raw_payment_terms_days").cast("int").alias("payment_terms_days"),
                        F.col("raw_onboard_date"),
                        F.col("raw_payment_terms_days"),
                        F.col("raw_active_flag").isin("y", "true").alias("is_active"),
                    )


                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-schema-normalization-gcp-golden").getOrCreate()
                    try:
                        config = read_json_config(spark, args.config)
                        canonical_columns = config["canonicalColumns"]
                        positive = spark.read.option("header", "true").csv(args.positive_input)
                        edge = spark.read.option("header", "true").csv(args.edge_input)
                        negative = spark.read.option("header", "true").csv(args.negative_input)

                        normalized = normalize(positive.unionByName(edge))
                        canonical = normalized.select(*canonical_columns).orderBy("supplier_id")
                        canonical.coalesce(1).write.mode("overwrite").option("header", "true").csv(
                            args.output + "/canonical-suppliers"
                        )

                        negative_normalized = normalize(negative)
                        rejects = negative_normalized.where(
                            F.col("onboard_date").isNull() | F.col("payment_terms_days").isNull()
                        ).select("supplier_id", "raw_onboard_date", "raw_payment_terms_days").orderBy("supplier_id")
                        rejects.coalesce(1).write.mode("overwrite").option("header", "true").csv(
                            args.output + "/normalization-rejects"
                        )
                        bad_cast_ids = [row["supplier_id"] for row in rejects.select("supplier_id").collect()]
                        report = {
                            "blueprintKey": "SchemaNormalization",
                            "canonicalColumns": canonical.columns,
                            "canonicalRowCount": canonical.count(),
                            "rejectRowCount": rejects.count(),
                            "badCastSupplierIds": bad_cast_ids,
                            "verdict": "draft-pass",
                        }
                        metrics = {
                            "canonicalRowCount": canonical.count(),
                            "rejectRowCount": rejects.count(),
                            "canonicalColumnCount": len(canonical.columns),
                        }
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(
                            args.output + "/normalization-report"
                        )
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(
                            args.output + "/normalization-metrics"
                        )
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
