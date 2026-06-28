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

class JsonStructGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0810";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String INPUT_ROOT = "gs://pulse-home-lending-dev-files/semantic/json-struct/input";
    private static final String CONFIG_URI = INPUT_ROOT + "/expectations/struct-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/json-struct/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("json-struct")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeJsonStructGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/transform/json_struct.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/json_struct_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, structJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written =
                new GcpComposerDataprocBridgeAdapter(objectMapper).writeEvidence(request(), EVIDENCE_ROOT);

        assertTrue(Files.exists(mainPython));
        assertTrue(Files.exists(secretResolver));
        assertTrue(Files.exists(dag));
        assertTrue(Files.readString(mainPython).contains("visit-structs"));
        assertTrue(Files.readString(mainPython).contains("rejectVisitIds"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_json_struct"));
        assertTrue(renderedDag.contains("semantic-json-struct-batch-20260506-0810"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("json-struct-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("semantic-json-struct-batch-20260506-0810", submit.get("batchId").asText());
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "json-struct-gcp-golden", RUN_ID, "pulse-proof-04261847", "us-central1",
                "pulse-proof-composer", "gs://us-central1-pulse-proof-com-a7066110-bucket/dags", "us-central1",
                DATAPROC_RUNTIME_SERVICE_ACCOUNT, STAGING_BUCKET, OUTPUT_PREFIX, NAMESPACE,
                "pulse_semantic_gcp_json_struct", "json_struct_gcp_dag.py",
                "pulse_semantic__json_struct_run_20260506_0810", "2026-05-06T08:10:00+00:00",
                "semantic-json-struct-batch-20260506-0810", "jobs/transform/json_struct.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of("--positive-input", INPUT_ROOT + "/positive/visits_flat.csv",
                        "--edge-input", INPUT_ROOT + "/edge/visits_optional_nulls.csv",
                        "--negative-input", INPUT_ROOT + "/negative/visits_invalid.csv",
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX),
                "GCLOUD_SUBPROCESS", "GCLOUD_COMPOSER_RUN", "aamer@aamer.net", RUNTIME_ROOT
        );
    }

    private String structJob() {
        return """
                from __future__ import annotations
                import argparse, json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F

                def parse_args():
                    p = argparse.ArgumentParser(description="JsonStruct semantic GCP golden proof job")
                    p.add_argument("--positive-input", required=True)
                    p.add_argument("--edge-input", required=True)
                    p.add_argument("--negative-input", required=True)
                    p.add_argument("--config", required=True)
                    p.add_argument("--output", required=True)
                    return p.parse_args()

                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))

                def read_visits(spark, uri):
                    return spark.read.option("header", "true").csv(uri).withColumn("charge_amount", F.col("charge_amount").cast("double"))

                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-json-struct-gcp-golden").getOrCreate()
                    try:
                        spark.conf.set("spark.sql.jsonGenerator.ignoreNullFields", "false")
                        read_json_config(spark, args.config)
                        visits = read_visits(spark, args.positive_input).unionByName(read_visits(spark, args.edge_input))
                        negative = read_visits(spark, args.negative_input)
                        rejects = negative.where(F.col("charge_amount") < 0).select("visit_id").orderBy("visit_id")
                        valid = visits.where(F.col("charge_amount") >= 0)
                        null_optional = [r["visit_id"] for r in valid.where(F.col("dob").isNull() | F.col("procedure_code").isNull()).select("visit_id").orderBy("visit_id").collect()]
                        structured = valid.select(
                            "visit_id",
                            F.to_json(F.struct("dob", "patient_id", "patient_name")).alias("patient"),
                            F.to_json(F.struct("provider_id")).alias("provider"),
                            F.to_json(F.struct("diagnosis_code", "procedure_code")).alias("clinical"),
                            F.col("charge_amount").cast("double").alias("charge_amount"),
                        ).orderBy("visit_id")
                        structured.coalesce(1).write.mode("overwrite").option("header", "true").option("quoteAll", "true").csv(args.output + "/visit-structs")
                        rejects.coalesce(1).write.mode("overwrite").option("header", "true").csv(args.output + "/struct-rejects")
                        reject_ids = [r["visit_id"] for r in rejects.collect()]
                        report = {"blueprintKey":"JsonStruct","structRowCount":structured.count(),"nullOptionalVisitIds":null_optional,"rejectVisitIds":reject_ids,"verdict":"draft-pass"}
                        metrics = {"structRowCount":structured.count(),"nullOptionalVisitCount":len(null_optional),"rejectRowCount":rejects.count()}
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(args.output + "/struct-report")
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(args.output + "/struct-metrics")
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
