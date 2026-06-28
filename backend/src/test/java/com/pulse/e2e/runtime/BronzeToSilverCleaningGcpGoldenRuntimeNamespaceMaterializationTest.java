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

class BronzeToSilverCleaningGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0710";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String INPUT_ROOT = "gs://pulse-home-lending-dev-files/semantic/bronze-to-silver-cleaning/input";
    private static final String CONFIG_URI = INPUT_ROOT + "/expectations/cleaning-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/bronze-to-silver-cleaning/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("bronze-to-silver-cleaning")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeBronzeToSilverCleaningGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/transform/bronze_to_silver_cleaning.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/bronze_to_silver_cleaning_gcp_dag_r2.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, cleaningJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written =
                new GcpComposerDataprocBridgeAdapter(objectMapper).writeEvidence(request(), EVIDENCE_ROOT);

        assertTrue(Files.exists(mainPython));
        assertTrue(Files.exists(secretResolver));
        assertTrue(Files.exists(dag));

        String job = Files.readString(mainPython);
        assertTrue(job.contains("clean-contacts"));
        assertTrue(job.contains("cleaning-rejects"));
        assertTrue(job.contains("rejectCustomerIds"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_bronze_to_silver_cleaning_r2"));
        assertTrue(renderedDag.contains("semantic-bronze-silver-cleaning-batch-20260506-0710"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("bronze-to-silver-cleaning-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("semantic-bronze-silver-cleaning-batch-20260506-0710", submit.get("batchId").asText());
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "bronze-to-silver-cleaning-gcp-golden",
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
                "pulse_semantic_gcp_bronze_to_silver_cleaning_r2",
                "bronze_to_silver_cleaning_gcp_dag_r2.py",
                "pulse_semantic__bronze_to_silver_cleaning_run_20260506_0710",
                "2026-05-06T06:40:00+00:00",
                "semantic-bronze-silver-cleaning-batch-20260506-0710",
                "jobs/transform/bronze_to_silver_cleaning.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of(
                        "--positive-input", INPUT_ROOT + "/positive/contacts_dirty.csv",
                        "--edge-input", INPUT_ROOT + "/edge/contacts_edge.csv",
                        "--negative-input", INPUT_ROOT + "/negative/contacts_invalid.csv",
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX
                ),
                "GCLOUD_SUBPROCESS",
                "GCLOUD_COMPOSER_RUN",
                "aamer@aamer.net",
                RUNTIME_ROOT
        );
    }

    private String cleaningJob() {
        return """
                from __future__ import annotations

                import argparse
                import json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F


                def parse_args():
                    parser = argparse.ArgumentParser(description="BronzeToSilverCleaning semantic GCP golden proof job")
                    parser.add_argument("--positive-input", required=True)
                    parser.add_argument("--edge-input", required=True)
                    parser.add_argument("--negative-input", required=True)
                    parser.add_argument("--config", required=True)
                    parser.add_argument("--output", required=True)
                    return parser.parse_args()


                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))


                def clean(df):
                    digits = F.regexp_replace(F.coalesce(F.col("phone"), F.lit("")), "[^0-9]", "")
                    return df.select(
                        F.col("customer_id"),
                        F.trim(F.col("name")).alias("name"),
                        F.lower(F.trim(F.col("email"))).alias("email"),
                        digits.alias("phone"),
                        F.upper(F.trim(F.col("state"))).alias("state"),
                        F.col("signup_date"),
                    )


                def invalid(df):
                    return df.where(
                        (~F.col("email").rlike("^[^@]+@[^@]+\\\\.[^@]+$"))
                        | ((F.col("phone") != "") & (~F.col("phone").rlike("^[0-9]{10,11}$")))
                    )


                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-bronze-silver-cleaning-gcp-golden").getOrCreate()
                    try:
                        read_json_config(spark, args.config)
                        positive = spark.read.option("header", "true").csv(args.positive_input)
                        edge = spark.read.option("header", "true").csv(args.edge_input)
                        negative = spark.read.option("header", "true").csv(args.negative_input)

                        clean_contacts = clean(positive.unionByName(edge)).orderBy("customer_id")
                        rejects = invalid(clean(negative)).orderBy("customer_id")
                        clean_contacts.coalesce(1).write.mode("overwrite").option("header", "true").csv(
                            args.output + "/clean-contacts"
                        )
                        rejects.coalesce(1).write.mode("overwrite").option("header", "true").csv(
                            args.output + "/cleaning-rejects"
                        )
                        reject_ids = [row["customer_id"] for row in rejects.select("customer_id").collect()]
                        blank_phone_ids = [
                            row["customer_id"]
                            for row in clean_contacts.where(F.col("phone") == "").select("customer_id").collect()
                        ]
                        report = {
                            "blueprintKey": "BronzeToSilverCleaning",
                            "cleanRowCount": clean_contacts.count(),
                            "rejectRowCount": rejects.count(),
                            "rejectCustomerIds": reject_ids,
                            "blankPhoneAllowedCustomerIds": blank_phone_ids,
                            "verdict": "draft-pass",
                        }
                        metrics = {
                            "cleanRowCount": clean_contacts.count(),
                            "rejectRowCount": rejects.count(),
                            "blankPhoneAllowedCount": len(blank_phone_ids),
                        }
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(
                            args.output + "/cleaning-report"
                        )
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(
                            args.output + "/cleaning-metrics"
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
