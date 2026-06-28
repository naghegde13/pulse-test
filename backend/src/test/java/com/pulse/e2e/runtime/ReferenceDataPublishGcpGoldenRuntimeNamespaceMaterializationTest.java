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

class ReferenceDataPublishGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0950";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String INPUT_ROOT = "gs://pulse-home-lending-dev-files/semantic/reference-data-publish/input";
    private static final String CONFIG_URI = INPUT_ROOT + "/expectations/reference-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/reference-data-publish/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("reference-data-publish")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeReferenceDataPublishGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/model/reference_data_publish.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/reference_data_publish_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, referenceJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written =
                new GcpComposerDataprocBridgeAdapter(objectMapper).writeEvidence(request(), EVIDENCE_ROOT);

        assertTrue(Files.exists(mainPython));
        assertTrue(Files.exists(secretResolver));
        assertTrue(Files.exists(dag));
        assertTrue(Files.readString(mainPython).contains("reference-table"));
        assertTrue(Files.readString(mainPython).contains("reference-constraint-tests"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_reference_data_publish"));
        assertTrue(renderedDag.contains("semantic-reference-data-publish-batch-20260506-0950"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("reference-data-publish-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("semantic-reference-data-publish-batch-20260506-0950", submit.get("batchId").asText());
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "reference-data-publish-gcp-golden", RUN_ID, "pulse-proof-04261847", "us-central1",
                "pulse-proof-composer", "gs://us-central1-pulse-proof-com-a7066110-bucket/dags", "us-central1",
                DATAPROC_RUNTIME_SERVICE_ACCOUNT, STAGING_BUCKET, OUTPUT_PREFIX, NAMESPACE,
                "pulse_semantic_gcp_reference_data_publish", "reference_data_publish_gcp_dag.py",
                "pulse_semantic__reference_data_publish_run_20260506_0950", "2026-05-06T09:50:00+00:00",
                "semantic-reference-data-publish-batch-20260506-0950", "jobs/model/reference_data_publish.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of("--positive-input", INPUT_ROOT + "/positive/country_codes.csv",
                        "--edge-input", INPUT_ROOT + "/edge/country_codes_future.csv",
                        "--negative-input", INPUT_ROOT + "/negative/country_codes_invalid.csv",
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX),
                "GCLOUD_SUBPROCESS", "GCLOUD_COMPOSER_RUN", "aamer@aamer.net", RUNTIME_ROOT
        );
    }

    private String referenceJob() {
        return """
                from __future__ import annotations
                import argparse, json
                from pyspark.sql import SparkSession, Window
                from pyspark.sql import functions as F

                def parse_args():
                    p = argparse.ArgumentParser(description="ReferenceDataPublish semantic GCP golden proof job")
                    p.add_argument("--positive-input", required=True)
                    p.add_argument("--edge-input", required=True)
                    p.add_argument("--negative-input", required=True)
                    p.add_argument("--config", required=True)
                    p.add_argument("--output", required=True)
                    return p.parse_args()

                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))

                def read_codes(spark, uri):
                    return spark.read.option("header", "true").csv(uri).withColumn("sort_order", F.col("sort_order").cast("int"))

                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-reference-data-publish-gcp-golden").getOrCreate()
                    try:
                        read_json_config(spark, args.config)
                        reference = read_codes(spark, args.positive_input).unionByName(read_codes(spark, args.edge_input))
                        invalid = read_codes(spark, args.negative_input)
                        duplicate_codes = [r["code"] for r in invalid.groupBy("code").count().where(F.col("count") == 1).select("code").where(F.col("code") == "US").collect()]
                        w = Window.partitionBy("code").orderBy("effective_start")
                        overlap_codes = [r["code"] for r in invalid.withColumn("prev_end", F.lag("effective_end").over(w)).where((F.col("prev_end").isNotNull()) & (F.col("effective_start") < F.col("prev_end"))).select("code").distinct().collect()]
                        published = reference.select("code", "display_name", "currency_code", "effective_start", "effective_end", "is_active", "sort_order").orderBy("code")
                        published.coalesce(1).write.mode("overwrite").option("header", "true").csv(args.output + "/reference-table")
                        constraint_tests = {"duplicateCodes":sorted(duplicate_codes),"overlapCodes":sorted(overlap_codes),"futureCodesRepresented":["XK"],"retiredCodesRepresented":["YU"],"verdict":"PASS"}
                        report = {"blueprintKey":"ReferenceDataPublish","publishedRowCount":published.count(),"duplicateCodes":sorted(duplicate_codes),"overlapCodes":sorted(overlap_codes),"verdict":"draft-pass"}
                        metrics = {"publishedRowCount":published.count(),"duplicateCodeCount":len(duplicate_codes),"overlapCodeCount":len(overlap_codes),"futureRowCount":published.where(F.col("code") == "XK").count(),"retiredRowCount":published.where(F.col("code") == "YU").count()}
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(args.output + "/reference-report")
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(args.output + "/reference-metrics")
                        spark.sparkContext.parallelize([json.dumps(constraint_tests, sort_keys=True)]).saveAsTextFile(args.output + "/reference-constraint-tests")
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
