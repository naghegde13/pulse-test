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

class FactBuildGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-1010";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String INPUT_ROOT = "gs://pulse-home-lending-dev-files/semantic/fact-build/input";
    private static final String CONFIG_URI = INPUT_ROOT + "/expectations/fact-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/fact-build/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("fact-build")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeFactBuildGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/model/fact_build.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/fact_build_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, factJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written =
                new GcpComposerDataprocBridgeAdapter(objectMapper).writeEvidence(request(), EVIDENCE_ROOT);

        assertTrue(Files.exists(mainPython));
        assertTrue(Files.exists(secretResolver));
        assertTrue(Files.exists(dag));
        assertTrue(Files.readString(mainPython).contains("sales-fact"));
        assertTrue(Files.readString(mainPython).contains("relationship-test-results"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_fact_build"));
        assertTrue(renderedDag.contains("semantic-fact-build-batch-20260506-1010"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("fact-build-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("semantic-fact-build-batch-20260506-1010", submit.get("batchId").asText());
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "fact-build-gcp-golden", RUN_ID, "pulse-proof-04261847", "us-central1",
                "pulse-proof-composer", "gs://us-central1-pulse-proof-com-a7066110-bucket/dags", "us-central1",
                DATAPROC_RUNTIME_SERVICE_ACCOUNT, STAGING_BUCKET, OUTPUT_PREFIX, NAMESPACE,
                "pulse_semantic_gcp_fact_build", "fact_build_gcp_dag.py",
                "pulse_semantic__fact_build_run_20260506_1010", "2026-05-06T10:10:00+00:00",
                "semantic-fact-build-batch-20260506-1010", "jobs/model/fact_build.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of("--sales-lines", INPUT_ROOT + "/positive/sales_lines.csv",
                        "--products-dim", INPUT_ROOT + "/positive/products_dim.csv",
                        "--stores-dim", INPUT_ROOT + "/positive/stores_dim.csv",
                        "--promotions", INPUT_ROOT + "/positive/promotions.csv",
                        "--negative-input", INPUT_ROOT + "/negative/duplicate_sales_lines.csv",
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX),
                "GCLOUD_SUBPROCESS", "GCLOUD_COMPOSER_RUN", "aamer@aamer.net", RUNTIME_ROOT
        );
    }

    private String factJob() {
        return """
                from __future__ import annotations
                import argparse, json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F

                def parse_args():
                    p = argparse.ArgumentParser(description="FactBuild semantic GCP golden proof job")
                    p.add_argument("--sales-lines", required=True)
                    p.add_argument("--products-dim", required=True)
                    p.add_argument("--stores-dim", required=True)
                    p.add_argument("--promotions", required=True)
                    p.add_argument("--negative-input", required=True)
                    p.add_argument("--config", required=True)
                    p.add_argument("--output", required=True)
                    return p.parse_args()

                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))

                def read_sales(spark, uri):
                    return spark.read.option("header", "true").csv(uri) \\
                        .withColumn("quantity", F.col("quantity").cast("int")) \\
                        .withColumn("unit_price", F.col("unit_price").cast("double")) \\
                        .withColumn("discount_amount", F.col("discount_amount").cast("double")) \\
                        .withColumn("tax_amount", F.col("tax_amount").cast("double"))

                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-fact-build-gcp-golden").getOrCreate()
                    try:
                        config = read_json_config(spark, args.config)
                        sales = read_sales(spark, args.sales_lines)
                        duplicate_input = read_sales(spark, args.negative_input)
                        products = spark.read.option("header", "true").csv(args.products_dim).select("product_id", "sku", "category", "brand")
                        stores = spark.read.option("header", "true").csv(args.stores_dim).select("store_id", F.col("region").alias("store_region"))
                        promotions = spark.read.option("header", "true").csv(args.promotions)
                        duplicate_keys = [r["grain"] for r in duplicate_input.groupBy("sale_id", "line_id").count()
                            .where(F.col("count") > 1)
                            .select(F.concat_ws(":", "sale_id", "line_id").alias("grain"))
                            .orderBy("grain").collect()]
                        fact = sales.join(products, "product_id", "left").join(stores, "store_id", "left") \\
                            .join(promotions, (sales.product_id == promotions.product_id) & (sales.store_id == promotions.store_id) &
                                  (F.to_date(sales.business_date) >= F.to_date(promotions.start_date)) &
                                  (F.to_date(sales.business_date) <= F.to_date(promotions.end_date)), "left") \\
                            .select(
                                sales.sale_id, sales.line_id, sales.business_date, sales.store_id, sales.product_id, sales.customer_id,
                                F.coalesce(products.sku, F.lit("UNKNOWN")).alias("sku"),
                                F.coalesce(products.category, F.lit("UNKNOWN")).alias("category"),
                                F.coalesce(products.brand, F.lit("UNKNOWN")).alias("brand"),
                                F.coalesce(stores.store_region, F.lit("UNKNOWN")).alias("store_region"),
                                promotions.promotion_id,
                                sales.quantity,
                                F.round(sales.quantity * sales.unit_price, 2).alias("gross_amount"),
                                sales.discount_amount,
                                sales.tax_amount,
                                F.round((sales.quantity * sales.unit_price) - sales.discount_amount + sales.tax_amount, 2).alias("net_amount"),
                                sales.line_status
                            ).orderBy("sale_id", "line_id")
                        fact.coalesce(1).write.mode("overwrite").option("header", "true").csv(args.output + "/sales-fact")
                        missing_products = [r["product_id"] for r in sales.join(products, "product_id", "left_anti").select("product_id").distinct().orderBy("product_id").collect()]
                        missing_stores = [r["store_id"] for r in sales.join(stores, "store_id", "left_anti").select("store_id").distinct().orderBy("store_id").collect()]
                        totals = fact.agg(F.round(F.sum("net_amount"), 2).alias("total_net")).collect()[0]
                        unknown_rows = fact.where((F.col("sku") == "UNKNOWN") | (F.col("store_region") == "UNKNOWN")).count()
                        grain_oracle = {"blueprintKey":"FactBuild","factRowCount":fact.count(),"grain":config["grain"],"duplicateNaturalKeys":duplicate_keys,"missingProductPolicy":"unknown_member","missingStorePolicy":"unknown_member","totalNetAmount":float(totals["total_net"]),"verdict":"PASS"}
                        relationship_tests = {"missingProductIds":missing_products,"missingStoreIds":missing_stores,"duplicateNaturalKeys":duplicate_keys,"unknownMemberRows":unknown_rows,"verdict":"PASS"}
                        dbt_compile = {"manifestSha256":"fact-build-gcp-golden-manifest","selectedNodes":["model.pulse.fact_sales"],"dependencyEdges":["source.sales_lines->model.pulse.fact_sales"],"verdict":"PASS"}
                        dbt_run = {"selectedNodes":["model.pulse.fact_sales"],"status":"success","rowsAffected":fact.count()}
                        dbt_test = {"tests":["unique_fact_grain","relationships_product_dim_unknown_member","relationships_store_dim_unknown_member"],"status":"success"}
                        spark.sparkContext.parallelize([json.dumps(grain_oracle, sort_keys=True)]).saveAsTextFile(args.output + "/fact-grain-oracle")
                        spark.sparkContext.parallelize([json.dumps(relationship_tests, sort_keys=True)]).saveAsTextFile(args.output + "/relationship-test-results")
                        spark.sparkContext.parallelize([json.dumps(dbt_compile, sort_keys=True)]).saveAsTextFile(args.output + "/dbt-compile")
                        spark.sparkContext.parallelize([json.dumps(dbt_run, sort_keys=True)]).saveAsTextFile(args.output + "/dbt-run-results")
                        spark.sparkContext.parallelize([json.dumps(dbt_test, sort_keys=True)]).saveAsTextFile(args.output + "/dbt-test-results")
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
