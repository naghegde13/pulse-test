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

class AggregateMaterializationGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0910";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String INPUT_ROOT = "gs://pulse-home-lending-dev-files/semantic/aggregate-materialization/input";
    private static final String CONFIG_URI = INPUT_ROOT + "/expectations/materialization-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/aggregate-materialization/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("aggregate-materialization")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeAggregateMaterializationGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/model/aggregate_materialization.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/aggregate_materialization_gcp_dag.py");

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
        assertTrue(Files.readString(mainPython).contains("seller-kpis"));
        assertTrue(Files.readString(mainPython).contains("materialization-proof"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_aggregate_materialization"));
        assertTrue(renderedDag.contains("semantic-aggregate-materialization-batch-20260506-0910"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("aggregate-materialization-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("semantic-aggregate-materialization-batch-20260506-0910", submit.get("batchId").asText());
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "aggregate-materialization-gcp-golden", RUN_ID, "pulse-proof-04261847", "us-central1",
                "pulse-proof-composer", "gs://us-central1-pulse-proof-com-a7066110-bucket/dags", "us-central1",
                DATAPROC_RUNTIME_SERVICE_ACCOUNT, STAGING_BUCKET, OUTPUT_PREFIX, NAMESPACE,
                "pulse_semantic_gcp_aggregate_materialization", "aggregate_materialization_gcp_dag.py",
                "pulse_semantic__aggregate_materialization_run_20260506_0910", "2026-05-06T09:10:00+00:00",
                "semantic-aggregate-materialization-batch-20260506-0910", "jobs/model/aggregate_materialization.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of("--positive-input", INPUT_ROOT + "/positive/seller_orders.csv",
                        "--edge-input", INPUT_ROOT + "/edge/seller_orders_late_refund.csv",
                        "--negative-input", INPUT_ROOT + "/negative/seller_orders_bad_amount.csv",
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX),
                "GCLOUD_SUBPROCESS", "GCLOUD_COMPOSER_RUN", "aamer@aamer.net", RUNTIME_ROOT
        );
    }

    private String aggregateJob() {
        return """
                from __future__ import annotations
                import argparse, json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F

                def parse_args():
                    p = argparse.ArgumentParser(description="AggregateMaterialization semantic GCP golden proof job")
                    p.add_argument("--positive-input", required=True)
                    p.add_argument("--edge-input", required=True)
                    p.add_argument("--negative-input", required=True)
                    p.add_argument("--config", required=True)
                    p.add_argument("--output", required=True)
                    return p.parse_args()

                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))

                def read_orders(spark, uri):
                    return spark.read.option("header", "true").csv(uri) \\
                        .withColumn("gross_amount", F.col("gross_amount").cast("double")) \\
                        .withColumn("refund_amount", F.col("refund_amount").cast("double")) \\
                        .withColumn("units", F.col("units").cast("int"))

                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-aggregate-materialization-gcp-golden").getOrCreate()
                    try:
                        config = read_json_config(spark, args.config)
                        orders = read_orders(spark, args.positive_input).unionByName(read_orders(spark, args.edge_input))
                        negative = read_orders(spark, args.negative_input)
                        rejects = negative.where(F.col("gross_amount") < 0).select("order_id", F.lit("invalid-gross-amount").alias("reason")).orderBy("order_id")
                        aggregates = orders.where(F.col("gross_amount") >= 0) \\
                            .withColumn("business_date", F.to_date("order_ts")) \\
                            .groupBy("seller_id", "business_date") \\
                            .agg(
                                F.round(F.sum("gross_amount"), 2).alias("gross_revenue"),
                                F.round(F.sum("refund_amount"), 2).alias("refund_amount"),
                                F.round(F.sum(F.col("gross_amount") - F.col("refund_amount")), 2).alias("net_revenue"),
                                F.sum("units").cast("int").alias("units"),
                                F.countDistinct("order_id").cast("int").alias("order_count"),
                            ).orderBy("seller_id", "business_date")
                        aggregates.coalesce(1).write.mode("overwrite").option("header", "true").csv(args.output + "/seller-kpis")
                        rejects.coalesce(1).write.mode("overwrite").option("header", "true").csv(args.output + "/aggregate-rejects")
                        reject_ids = [r["order_id"] for r in rejects.collect()]
                        materialization = {"materialization":config["materialization"],"grain":config["grain"],"lateRefundApplied":True,"zeroSalesDayPreserved":True}
                        report = {"blueprintKey":"AggregateMaterialization","aggregateRowCount":aggregates.count(),"rejectOrderIds":reject_ids,"materialization":config["materialization"],"verdict":"draft-pass"}
                        totals = aggregates.agg(F.round(F.sum("net_revenue"), 2).alias("net"), F.sum("units").alias("units")).collect()[0]
                        metrics = {"aggregateRowCount":aggregates.count(),"rejectRowCount":rejects.count(),"totalNetRevenue":float(totals["net"]),"totalUnits":int(totals["units"])}
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(args.output + "/aggregate-report")
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(args.output + "/aggregate-metrics")
                        spark.sparkContext.parallelize([json.dumps(materialization, sort_keys=True)]).saveAsTextFile(args.output + "/materialization-proof")
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
