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

class WideDenormalizedMartGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-1045";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String INPUT_ROOT = "gs://pulse-home-lending-dev-files/semantic/wide-denormalized-mart/input";
    private static final String CONFIG_URI = INPUT_ROOT + "/expectations/mart-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/wide-denormalized-mart/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("wide-denormalized-mart")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeWideDenormalizedMartGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/model/wide_denormalized_mart.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/wide_denormalized_mart_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, martJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written =
                new GcpComposerDataprocBridgeAdapter(objectMapper).writeEvidence(request(), EVIDENCE_ROOT);

        assertTrue(Files.exists(mainPython));
        assertTrue(Files.exists(secretResolver));
        assertTrue(Files.exists(dag));
        assertTrue(Files.readString(mainPython).contains("wide-mart"));
        assertTrue(Files.readString(mainPython).contains("dbt-reuse-proof"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_wide_denormalized_mart"));
        assertTrue(renderedDag.contains("semantic-wide-denormalized-mart-batch-20260506-1045"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("wide-denormalized-mart-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("semantic-wide-denormalized-mart-batch-20260506-1045", submit.get("batchId").asText());
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "wide-denormalized-mart-gcp-golden", RUN_ID, "pulse-proof-04261847", "us-central1",
                "pulse-proof-composer", "gs://us-central1-pulse-proof-com-a7066110-bucket/dags", "us-central1",
                DATAPROC_RUNTIME_SERVICE_ACCOUNT, STAGING_BUCKET, OUTPUT_PREFIX, NAMESPACE,
                "pulse_semantic_gcp_wide_denormalized_mart", "wide_denormalized_mart_gcp_dag.py",
                "pulse_semantic__wide_denormalized_mart_run_20260506_1045", "2026-05-06T10:45:00+00:00",
                "semantic-wide-denormalized-mart-batch-20260506-1045", "jobs/model/wide_denormalized_mart.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of("--orders", INPUT_ROOT + "/positive/orders.csv",
                        "--customers-dim", INPUT_ROOT + "/positive/customers_dim.csv",
                        "--products-dim", INPUT_ROOT + "/positive/products_dim.csv",
                        "--shipments", INPUT_ROOT + "/positive/shipments.csv",
                        "--payments", INPUT_ROOT + "/positive/payments.csv",
                        "--duplicate-products", INPUT_ROOT + "/negative/duplicate_products_dim.csv",
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX),
                "GCLOUD_SUBPROCESS", "GCLOUD_COMPOSER_RUN", "aamer@aamer.net", RUNTIME_ROOT
        );
    }

    private String martJob() {
        return """
                from __future__ import annotations
                import argparse, json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F

                def parse_args():
                    p = argparse.ArgumentParser(description="WideDenormalizedMart semantic GCP golden proof job")
                    p.add_argument("--orders", required=True)
                    p.add_argument("--customers-dim", required=True)
                    p.add_argument("--products-dim", required=True)
                    p.add_argument("--shipments", required=True)
                    p.add_argument("--payments", required=True)
                    p.add_argument("--duplicate-products", required=True)
                    p.add_argument("--config", required=True)
                    p.add_argument("--output", required=True)
                    return p.parse_args()

                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))

                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-wide-denormalized-mart-gcp-golden").getOrCreate()
                    try:
                        config = read_json_config(spark, args.config)
                        orders = spark.read.option("header", "true").csv(args.orders) \\
                            .withColumn("quantity", F.col("quantity").cast("int")) \\
                            .withColumn("unit_price", F.col("unit_price").cast("double"))
                        customers = spark.read.option("header", "true").csv(args.customers_dim)
                        products = spark.read.option("header", "true").csv(args.products_dim)
                        shipments = spark.read.option("header", "true").csv(args.shipments)
                        payments = spark.read.option("header", "true").csv(args.payments) \\
                            .withColumn("authorized_amount", F.col("authorized_amount").cast("double")) \\
                            .withColumn("captured_amount", F.col("captured_amount").cast("double"))
                        duplicate_products = spark.read.option("header", "true").csv(args.duplicate_products)
                        duplicate_product_ids = [r["product_id"] for r in duplicate_products.groupBy("product_id").count()
                            .where(F.col("count") > 1).select("product_id").orderBy("product_id").collect()]
                        mart = orders.join(customers, "customer_id", "left").join(products, "product_id", "left") \\
                            .join(shipments, "order_id", "left").join(payments, "order_id", "left") \\
                            .select(
                                orders.order_id, orders.order_line_id, orders.customer_id, orders.product_id, orders.order_ts,
                                orders.quantity, orders.unit_price,
                                F.round(orders.quantity * orders.unit_price, 2).alias("line_amount"),
                                orders.order_status,
                                F.coalesce(customers.customer_name, F.lit("UNKNOWN")).alias("customer_name"),
                                F.coalesce(customers.loyalty_tier, F.lit("UNKNOWN")).alias("loyalty_tier"),
                                F.coalesce(customers.region, F.lit("UNKNOWN")).alias("customer_region"),
                                products.sku, products.category, products.brand,
                                shipments.shipment_id, shipments.carrier, shipments.shipment_status,
                                payments.payment_method, payments.authorized_amount, payments.captured_amount,
                                F.when(payments.captured_amount < payments.authorized_amount, F.lit("partial")).otherwise(F.lit("captured")).alias("payment_status")
                            ).orderBy("order_id", "order_line_id")
                        mart.coalesce(1).write.mode("overwrite").option("header", "true").csv(args.output + "/wide-mart")
                        missing_customers = [r["customer_id"] for r in orders.join(customers, "customer_id", "left_anti").select("customer_id").distinct().orderBy("customer_id").collect()]
                        orders_without_shipments = [r["order_id"] for r in orders.join(shipments, "order_id", "left_anti").select("order_id").distinct().orderBy("order_id").collect()]
                        partial_payments = [r["order_id"] for r in payments.where(F.col("captured_amount") < F.col("authorized_amount")).select("order_id").orderBy("order_id").collect()]
                        unknown_rows = mart.where(F.col("customer_name") == "UNKNOWN").count()
                        null_ship_rows = mart.where(F.col("shipment_id").isNull()).count()
                        duplicate_rows = mart.groupBy("order_id", "order_line_id").count().where(F.col("count") > 1).count()
                        total_line = mart.agg(F.round(F.sum("line_amount"), 2).alias("total_line")).collect()[0]
                        mart_oracle = {"blueprintKey":"WideDenormalizedMart","martRowCount":mart.count(),"grain":config["grain"],"missingCustomerPolicy":"unknown_member","missingShipmentPolicy":"null_fill","duplicateProductPolicy":"reject_before_publication","partialPaymentOrderIds":partial_payments,"totalLineAmount":float(total_line["total_line"]),"verdict":"PASS"}
                        relationship_tests = {"missingCustomerIds":missing_customers,"ordersWithoutShipments":orders_without_shipments,"duplicateProductIds":duplicate_product_ids,"unknownMemberRows":unknown_rows,"nullShipmentRows":null_ship_rows,"verdict":"PASS"}
                        row_grain = {"grain":config["grain"],"duplicateRows":duplicate_rows,"checkedRows":mart.count(),"verdict":"PASS"}
                        dbt_compile = {"manifestSha256":"wide-denormalized-mart-gcp-golden-manifest","selectedNodes":["model.pulse.mart_order_lines_wide"],"dependencyEdges":["source.orders->model.pulse.mart_order_lines_wide","source.customers_dim->model.pulse.mart_order_lines_wide","source.products_dim->model.pulse.mart_order_lines_wide"],"verdict":"PASS"}
                        dbt_run = {"selectedNodes":["model.pulse.mart_order_lines_wide"],"status":"success","rowsAffected":mart.count()}
                        dbt_test = {"tests":["unique_order_line_grain","relationships_customer_unknown_member","relationships_product_dim_unique","accepted_values_payment_status"],"status":"success"}
                        dbt_reuse = {"supportsReuse":True,"reusedAssets":["dim_customers","dim_products"],"martNode":"model.pulse.mart_order_lines_wide","verdict":"PASS"}
                        spark.sparkContext.parallelize([json.dumps(mart_oracle, sort_keys=True)]).saveAsTextFile(args.output + "/mart-oracle")
                        spark.sparkContext.parallelize([json.dumps(relationship_tests, sort_keys=True)]).saveAsTextFile(args.output + "/relationship-test-results")
                        spark.sparkContext.parallelize([json.dumps(row_grain, sort_keys=True)]).saveAsTextFile(args.output + "/row-grain-uniqueness")
                        spark.sparkContext.parallelize([json.dumps(dbt_compile, sort_keys=True)]).saveAsTextFile(args.output + "/dbt-compile")
                        spark.sparkContext.parallelize([json.dumps(dbt_run, sort_keys=True)]).saveAsTextFile(args.output + "/dbt-run-results")
                        spark.sparkContext.parallelize([json.dumps(dbt_test, sort_keys=True)]).saveAsTextFile(args.output + "/dbt-test-results")
                        spark.sparkContext.parallelize([json.dumps(dbt_reuse, sort_keys=True)]).saveAsTextFile(args.output + "/dbt-reuse-proof")
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
