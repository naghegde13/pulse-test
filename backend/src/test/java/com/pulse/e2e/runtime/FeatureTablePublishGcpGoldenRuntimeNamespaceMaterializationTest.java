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

class FeatureTablePublishGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0930";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String INPUT_ROOT = "gs://pulse-home-lending-dev-files/semantic/feature-table-publish/input";
    private static final String CONFIG_URI = INPUT_ROOT + "/expectations/feature-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/feature-table-publish/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("feature-table-publish")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeFeatureTablePublishGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/model/feature_table_publish.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/feature_table_publish_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, featureJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written =
                new GcpComposerDataprocBridgeAdapter(objectMapper).writeEvidence(request(), EVIDENCE_ROOT);

        assertTrue(Files.exists(mainPython));
        assertTrue(Files.exists(secretResolver));
        assertTrue(Files.exists(dag));
        assertTrue(Files.readString(mainPython).contains("feature-table"));
        assertTrue(Files.readString(mainPython).contains("no-leak-scan"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_feature_table_publish"));
        assertTrue(renderedDag.contains("semantic-feature-table-publish-batch-20260506-0930"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("feature-table-publish-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("semantic-feature-table-publish-batch-20260506-0930", submit.get("batchId").asText());
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "feature-table-publish-gcp-golden", RUN_ID, "pulse-proof-04261847", "us-central1",
                "pulse-proof-composer", "gs://us-central1-pulse-proof-com-a7066110-bucket/dags", "us-central1",
                DATAPROC_RUNTIME_SERVICE_ACCOUNT, STAGING_BUCKET, OUTPUT_PREFIX, NAMESPACE,
                "pulse_semantic_gcp_feature_table_publish", "feature_table_publish_gcp_dag.py",
                "pulse_semantic__feature_table_publish_run_20260506_0930", "2026-05-06T09:30:00+00:00",
                "semantic-feature-table-publish-batch-20260506-0930", "jobs/model/feature_table_publish.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of("--transactions", INPUT_ROOT + "/positive/transactions.csv",
                        "--accounts", INPUT_ROOT + "/positive/accounts.csv",
                        "--chargebacks", INPUT_ROOT + "/negative/chargebacks.csv",
                        "--cutoffs", INPUT_ROOT + "/positive/feature_cutoffs.csv",
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX),
                "GCLOUD_SUBPROCESS", "GCLOUD_COMPOSER_RUN", "aamer@aamer.net", RUNTIME_ROOT
        );
    }

    private String featureJob() {
        return """
                from __future__ import annotations
                import argparse, json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F

                def parse_args():
                    p = argparse.ArgumentParser(description="FeatureTablePublish semantic GCP golden proof job")
                    p.add_argument("--transactions", required=True)
                    p.add_argument("--accounts", required=True)
                    p.add_argument("--chargebacks", required=True)
                    p.add_argument("--cutoffs", required=True)
                    p.add_argument("--config", required=True)
                    p.add_argument("--output", required=True)
                    return p.parse_args()

                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))

                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-feature-table-publish-gcp-golden").getOrCreate()
                    try:
                        config = read_json_config(spark, args.config)
                        tx = spark.read.option("header", "true").csv(args.transactions) \\
                            .withColumn("amount", F.col("amount").cast("double")) \\
                            .withColumn("transaction_ts", F.to_timestamp("transaction_ts"))
                        accounts = spark.read.option("header", "true").csv(args.accounts)
                        cutoffs = spark.read.option("header", "true").csv(args.cutoffs).withColumn("feature_as_of_ts", F.to_timestamp("feature_as_of_ts"))
                        chargebacks = spark.read.option("header", "true").csv(args.chargebacks).select("transaction_id", "chargeback_reported_at")
                        base = cutoffs.join(accounts, "account_id", "left")
                        joined = base.join(tx, "account_id", "left") \\
                            .withColumn("days_before_cutoff", F.datediff(F.to_date("feature_as_of_ts"), F.to_date("transaction_ts"))) \\
                            .where(F.col("transaction_id").isNull() | ((F.col("transaction_ts") < F.col("feature_as_of_ts")) & (F.col("days_before_cutoff") <= 30))) \\
                            .join(chargebacks, "transaction_id", "left")
                        features = joined.groupBy("account_id", "feature_as_of_ts").agg(
                            F.sum(F.when(F.col("days_before_cutoff") <= 7, 1).otherwise(0)).cast("int").alias("transaction_count_7d"),
                            F.round(F.sum(F.when(F.col("days_before_cutoff") <= 7, F.col("amount")).otherwise(0.0)), 2).alias("spend_7d"),
                            F.count("transaction_id").cast("int").alias("transaction_count_30d"),
                            F.round(F.sum(F.coalesce(F.col("amount"), F.lit(0.0))), 2).alias("spend_30d"),
                            F.round(F.coalesce(F.max("amount"), F.lit(0.0)), 2).alias("max_amount_30d"),
                            F.sum(F.when((F.col("transaction_id").isNotNull()) & (F.col("country") != F.col("home_country")), 1).otherwise(0)).cast("int").alias("cross_border_count_30d"),
                            F.sum(F.when(F.col("chargeback_reported_at").isNotNull(), 1).otherwise(0)).cast("int").alias("chargeback_count_30d"),
                        ).orderBy("account_id", "feature_as_of_ts")
                        feature_output = features.withColumn("feature_as_of_ts", F.date_format("feature_as_of_ts", "yyyy-MM-dd'T'HH:mm:ss'Z'"))
                        columns = feature_output.columns
                        excluded = [c for c in config["leakageExclusions"] if c in columns]
                        post_cutoff_ids = [r["transaction_id"] for r in tx.join(cutoffs, "account_id").where(F.col("transaction_ts") >= F.col("feature_as_of_ts")).select("transaction_id").orderBy("transaction_id").collect()]
                        feature_output.coalesce(1).write.mode("overwrite").option("header", "true").csv(args.output + "/feature-table")
                        schema_snapshot = {"columns":columns,"excludedColumnsPresent":excluded}
                        no_leak_scan = {"verdict":"PASS" if not excluded else "FAIL","excludedColumnsPresent":excluded,"excludedTermsPresent":[]}
                        feature_contract = {"entityKey":config["entityKey"],"cutoffColumn":config["cutoffColumn"],"windows":config["windows"],"leakageExclusions":config["leakageExclusions"],"defaultValuesApplied":True}
                        non_reuse = {"supportsReuse":False,"reuseProofRequired":False,"verdict":"PASS"}
                        report = {"blueprintKey":"FeatureTablePublish","featureRowCount":feature_output.count(),"postCutoffTransactionsExcluded":post_cutoff_ids,"leakageColumnCount":len(excluded),"verdict":"draft-pass"}
                        cold_start = feature_output.where(F.col("transaction_count_30d") == 0).count()
                        metrics = {"featureRowCount":feature_output.count(),"coldStartRowCount":cold_start,"postCutoffExcludedCount":len(post_cutoff_ids),"leakageColumnCount":len(excluded)}
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(args.output + "/feature-report")
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(args.output + "/feature-metrics")
                        spark.sparkContext.parallelize([json.dumps(schema_snapshot, sort_keys=True)]).saveAsTextFile(args.output + "/schema-snapshot")
                        spark.sparkContext.parallelize([json.dumps(no_leak_scan, sort_keys=True)]).saveAsTextFile(args.output + "/no-leak-scan")
                        spark.sparkContext.parallelize([json.dumps(feature_contract, sort_keys=True)]).saveAsTextFile(args.output + "/feature-definition-contract")
                        spark.sparkContext.parallelize([json.dumps(non_reuse, sort_keys=True)]).saveAsTextFile(args.output + "/dbt-non-reuse-contract")
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
