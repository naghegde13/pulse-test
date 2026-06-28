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

class DQValidatorGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0420";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String CLEAN_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/dq-validator/input/positive/claims_clean.csv";
    private static final String DIRTY_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/dq-validator/input/negative/claims_dirty.csv";
    private static final String RULES_URI = "gs://pulse-home-lending-dev-files/semantic/dq-validator/input/expectations/dq-rules.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/dq-validator/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("dq-validator")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeDqValidatorGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/dq/dq_validator.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/dq_validator_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, dqValidatorJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter adapter = new GcpComposerDataprocBridgeAdapter(objectMapper);
        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written = adapter.writeEvidence(
                request(),
                EVIDENCE_ROOT
        );

        assertTrue(Files.exists(mainPython), "main DQValidator PySpark job must exist");
        assertTrue(Files.exists(secretResolver), "runtime secret resolver helper must exist");
        assertTrue(Files.exists(dag), "Composer DAG must exist in the runtime namespace");

        String job = Files.readString(mainPython);
        assertTrue(job.contains("nullMemberIds"));
        assertTrue(job.contains("negativeAmountIds"));
        assertTrue(job.contains("invalidStatusIds"));
        assertTrue(job.contains("quarantineRequired"));
        assertTrue(job.contains("dirtyBatchFailureCount"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_dq_validator"));
        assertTrue(renderedDag.contains("semantic-dq-validator-batch-20260506-0420"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(namespacedRoot.resolve("runtime/dataproc-submit-request.json")));
        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("dq-validator-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("pulse-proof-04261847", submit.get("projectId").asText());
        assertEquals("semantic-dq-validator-batch-20260506-0420", submit.get("batchId").asText());
        assertEquals(
                STAGING_BUCKET + "/pulse-runtime/dq-validator-gcp-golden/" + RUN_ID + "/"
                        + NAMESPACE + "/jobs/dq/dq_validator.py",
                submit.at("/batch/pyspark_batch/main_python_file_uri").asText()
        );
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "dq-validator-gcp-golden",
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
                "pulse_semantic_gcp_dq_validator",
                "dq_validator_gcp_dag.py",
                "pulse_semantic__dq_validator_run_20260506_0420",
                "2026-05-06T04:20:00+00:00",
                "semantic-dq-validator-batch-20260506-0420",
                "jobs/dq/dq_validator.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of(
                        "--clean-input", CLEAN_FIXTURE_URI,
                        "--dirty-input", DIRTY_FIXTURE_URI,
                        "--rules", RULES_URI,
                        "--output", OUTPUT_PREFIX
                ),
                "GCLOUD_SUBPROCESS",
                "GCLOUD_COMPOSER_RUN",
                "aamer@aamer.net",
                RUNTIME_ROOT
        );
    }

    private String dqValidatorJob() {
        return """
                from __future__ import annotations

                import argparse
                import json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F


                def parse_args():
                    parser = argparse.ArgumentParser(description="DQValidator semantic GCP golden proof job")
                    parser.add_argument("--clean-input", required=True)
                    parser.add_argument("--dirty-input", required=True)
                    parser.add_argument("--rules", required=True)
                    parser.add_argument("--output", required=True)
                    return parser.parse_args()


                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-dq-validator-gcp-golden").getOrCreate()
                    try:
                        clean = spark.read.option("header", "true").csv(args.clean_input)
                        dirty = spark.read.option("header", "true").csv(args.dirty_input)

                        accepted = clean.select(
                            "claim_id",
                            "member_id",
                            "provider_id",
                            "service_date",
                            "charge_amount",
                            "status",
                        ).orderBy("claim_id")
                        accepted.coalesce(1).write.mode("overwrite").option("header", "true").csv(
                            args.output + "/accepted-claims"
                        )

                        null_member_ids = [
                            row["claim_id"]
                            for row in dirty.where(F.col("member_id").isNull() | (F.col("member_id") == ""))
                                .select("claim_id")
                                .orderBy("claim_id")
                                .collect()
                        ]
                        negative_amount_ids = [
                            row["claim_id"]
                            for row in dirty.where(F.col("charge_amount").cast("double") < F.lit(0.0))
                                .select("claim_id")
                                .orderBy("claim_id")
                                .collect()
                        ]
                        invalid_status_ids = [
                            row["claim_id"]
                            for row in dirty.where(~F.col("status").isin("APPROVED", "PENDING", "REJECTED"))
                                .select("claim_id")
                                .orderBy("claim_id")
                                .collect()
                        ]

                        accepted_count = accepted.count()
                        rejected_claim_ids = sorted(set(null_member_ids + negative_amount_ids + invalid_status_ids))
                        rejected_count = len(rejected_claim_ids)

                        report = {
                            "acceptedCount": accepted_count,
                            "rejectedCount": rejected_count,
                            "nullMemberIds": null_member_ids,
                            "negativeAmountIds": negative_amount_ids,
                            "invalidStatusIds": invalid_status_ids,
                            "quarantineRequired": True,
                        }
                        metrics = {
                            "acceptedCount": accepted_count,
                            "rejectedCount": rejected_count,
                            "dirtyBatchFailureCount": 1 if rejected_count > 0 else 0,
                        }
                        quarantine_probe = {
                            "blueprintKey": "DQValidator",
                            "scenarioId": "dq-validator-gcp-golden",
                            "rejectedClaimIds": rejected_claim_ids,
                            "quarantineRequired": True,
                            "rulesUri": args.rules,
                        }
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(
                            args.output + "/dq-report"
                        )
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(
                            args.output + "/dq-metrics"
                        )
                        spark.sparkContext.parallelize([json.dumps(quarantine_probe, sort_keys=True)]).saveAsTextFile(
                            args.output + "/quarantine-probe"
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
