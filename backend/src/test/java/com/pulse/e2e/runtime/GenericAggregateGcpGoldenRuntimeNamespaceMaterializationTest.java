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

class GenericAggregateGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0725";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String INPUT_ROOT = "gs://pulse-home-lending-dev-files/semantic/generic-aggregate/input";
    private static final String CONFIG_URI = INPUT_ROOT + "/expectations/aggregate-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/generic-aggregate/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("generic-aggregate")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeGenericAggregateGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/transform/generic_aggregate.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/generic_aggregate_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, aggregateJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written =
                new GcpComposerDataprocBridgeAdapter(objectMapper).writeEvidence(request(), EVIDENCE_ROOT);

        assertTrue(Files.exists(mainPython));
        assertTrue(Files.exists(secretResolver));
        assertTrue(Files.exists(dag));
        assertTrue(Files.readString(mainPython).contains("aggregate-output"));
        assertTrue(Files.readString(mainPython).contains("rejectLineIds"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_generic_aggregate"));
        assertTrue(renderedDag.contains("semantic-generic-aggregate-batch-20260506-0725"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("generic-aggregate-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("semantic-generic-aggregate-batch-20260506-0725", submit.get("batchId").asText());
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "generic-aggregate-gcp-golden",
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
                "pulse_semantic_gcp_generic_aggregate",
                "generic_aggregate_gcp_dag.py",
                "pulse_semantic__generic_aggregate_run_20260506_0725",
                "2026-05-06T07:25:00+00:00",
                "semantic-generic-aggregate-batch-20260506-0725",
                "jobs/transform/generic_aggregate.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of(
                        "--positive-input", INPUT_ROOT + "/positive/sales_lines.csv",
                        "--edge-input", INPUT_ROOT + "/edge/sales_edge.csv",
                        "--negative-input", INPUT_ROOT + "/negative/sales_invalid.csv",
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX
                ),
                "GCLOUD_SUBPROCESS",
                "GCLOUD_COMPOSER_RUN",
                "aamer@aamer.net",
                RUNTIME_ROOT
        );
    }

    private String aggregateJob() {
        return """
                from __future__ import annotations

                import argparse
                import json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F

                def parse_args():
                    parser = argparse.ArgumentParser(description="GenericAggregate semantic GCP golden proof job")
                    parser.add_argument("--positive-input", required=True)
                    parser.add_argument("--edge-input", required=True)
                    parser.add_argument("--negative-input", required=True)
                    parser.add_argument("--config", required=True)
                    parser.add_argument("--output", required=True)
                    return parser.parse_args()

                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))

                def typed(df):
                    return df.select(
                        "line_id", "store_id", "sale_date", "category", "sku", "line_type",
                        F.col("quantity").cast("int").alias("quantity"),
                        F.col("unit_price").cast("double").alias("unit_price"),
                    ).withColumn("revenue", F.col("quantity") * F.col("unit_price"))

                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-generic-aggregate-gcp-golden").getOrCreate()
                    try:
                        read_json_config(spark, args.config)
                        positive = typed(spark.read.option("header", "true").csv(args.positive_input))
                        edge = typed(spark.read.option("header", "true").csv(args.edge_input))
                        negative = typed(spark.read.option("header", "true").csv(args.negative_input))

                        valid = positive.unionByName(edge)
                        rejects = negative.where((F.col("quantity") < 0) & (F.col("line_type") != "return"))
                        aggregate = valid.groupBy("store_id", "sale_date", "category").agg(
                            F.sum("quantity").cast("int").alias("sum_quantity"),
                            F.round(F.sum("revenue"), 2).alias("sum_revenue"),
                            F.count("*").cast("int").alias("line_count"),
                            F.min("unit_price").alias("min_unit_price"),
                            F.max("unit_price").alias("max_unit_price"),
                        ).orderBy("store_id", "sale_date", "category")
                        aggregate.coalesce(1).write.mode("overwrite").option("header", "true").csv(
                            args.output + "/aggregate-output"
                        )
                        rejects.coalesce(1).write.mode("overwrite").option("header", "true").csv(
                            args.output + "/aggregate-rejects"
                        )
                        reject_ids = [row["line_id"] for row in rejects.select("line_id").orderBy("line_id").collect()]
                        report = {
                            "blueprintKey": "GenericAggregate",
                            "aggregateRowCount": aggregate.count(),
                            "inputRowCount": valid.count(),
                            "rejectRowCount": rejects.count(),
                            "rejectLineIds": reject_ids,
                            "verdict": "draft-pass",
                        }
                        metrics = {
                            "aggregateRowCount": aggregate.count(),
                            "inputRowCount": valid.count(),
                            "rejectRowCount": rejects.count(),
                        }
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(
                            args.output + "/aggregate-report"
                        )
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(
                            args.output + "/aggregate-metrics"
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
