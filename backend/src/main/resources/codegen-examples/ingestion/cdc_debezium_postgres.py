# PULSE codegen example: CDCIngestion — Debezium CDC stream from
# Postgres into bronze.
#
# What this blueprint does (and what it does NOT):
#   - Subscribes to the Debezium-emitted Kafka topic for a single
#     source table, parses the JSON envelope, and lands change events
#     in bronze. ONE row per CDC event (insert, update, delete).
#   - Bronze CDC table is APPEND-only — per-event audit trail. The
#     consumer that materializes the current-state view (silver
#     dimension or fact) is downstream (BronzeToSilverCleaning, then
#     SCD2Dimension or IncrementalMerge).
#   - Streaming, not micro-batch overwrite — uses Spark Structured
#     Streaming with Delta sink and a checkpoint location. Restartable
#     from the checkpoint on job failure with no data loss.
#   - Companion (NOT replacement): cdc_incremental_poll.py is the
#     polling fallback when Debezium isn't available. SAME blueprint,
#     different runtime mode. The agent picks the variant from the
#     `cdc_mode` param.

from datetime import date
import logging
import os

from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from pyspark.sql.types import StringType, StructField, StructType


RUN_ID = os.environ["PULSE_RUN_ID"]
PULSE_BUSINESS_DATE = os.environ.get("PULSE_BUSINESS_DATE", date.today().isoformat())
PULSE_PROCESSING_TS = os.environ.get("PULSE_PROCESSING_TS", "{{ ts }}")


PIPELINE_NAME = "__PIPELINE_NAME__"
TENANT_SLUG = "__TENANT_SLUG__"
DOMAIN_SLUG = "__DOMAIN_SLUG__"
SOR_SLUG = "__SOR_SLUG__"

DEBEZIUM_TOPIC = "__DEBEZIUM_TOPIC__"             # 'orders.public.shipments'
KAFKA_GROUP_ID = "__KAFKA_GROUP_ID__"             # one per pipeline; never shared
STARTING_OFFSETS = "__STARTING_OFFSETS__"         # 'earliest' | 'latest'
TRIGGER_INTERVAL = "__TRIGGER_INTERVAL__"         # '30 seconds' | '5 minutes'
MAX_OFFSETS_PER_TRIGGER = __MAX_OFFSETS_PER_TRIGGER__  # int; bound per-batch volume

# Storage convention (#30).
LAKE_FORMAT = "__LAKE_FORMAT__"        # 'delta' | 'iceberg_external' for streaming
STORAGE_BACKEND = "__STORAGE_BACKEND__"
TABLE_PATH = "__TABLE_PATH__"          # CDC bronze table directory URI
BQ_TABLE = "__BQ_TABLE__"
CHECKPOINT_PATH = "__CHECKPOINT_PATH__"  # _checkpoints/<stream_name>/ — per-pipeline, NEVER shared


# Secrets from Airflow connection / secret manager.
KAFKA_BOOTSTRAP = os.environ["__SOURCE_NAME___BOOTSTRAP_SERVERS"]
KAFKA_USERNAME = os.environ.get("__SOURCE_NAME___KAFKA_USERNAME")
KAFKA_PASSWORD = os.environ.get("__SOURCE_NAME___KAFKA_PASSWORD")


log = logging.getLogger("pulse.cdc_debezium")
log.setLevel(logging.INFO)


# Debezium envelope schema. We keep `before`/`after` as raw JSON strings
# so the silver layer can apply a typed schema *with column-level
# evolution rules* — bronze is intentionally schema-on-read.
ENVELOPE_SCHEMA = StructType([
    StructField("op", StringType()),                # 'c' | 'u' | 'd' | 'r' | 't'
    StructField("before", StringType()),
    StructField("after", StringType()),
    StructField("ts_ms", StringType()),
    StructField("source", StringType()),            # nested doc kept as JSON
    StructField("transaction", StringType()),
])


def kafka_read_options():
    options = {
        "kafka.bootstrap.servers": KAFKA_BOOTSTRAP,
        "subscribe": DEBEZIUM_TOPIC,
        "startingOffsets": STARTING_OFFSETS,
        "kafka.group.id": KAFKA_GROUP_ID,
        "maxOffsetsPerTrigger": str(MAX_OFFSETS_PER_TRIGGER),
        "failOnDataLoss": "false",
    }
    if KAFKA_USERNAME and KAFKA_PASSWORD:
        # SASL_SSL is the locked-default for managed Kafka offerings;
        # plaintext bootstrap is dev-local only.
        options.update({
            "kafka.security.protocol": "SASL_SSL",
            "kafka.sasl.mechanism": "SCRAM-SHA-512",
            "kafka.sasl.jaas.config": (
                "org.apache.kafka.common.security.scram.ScramLoginModule "
                f"required username=\"{KAFKA_USERNAME}\" "
                f"password=\"{KAFKA_PASSWORD}\";"
            ),
        })
    return options


def main():
    spark = (
        SparkSession.builder
        .appName(f"pulse-cdc-debezium-{PIPELINE_NAME}-{RUN_ID}")
        .config("spark.sql.streaming.schemaInference", "true")
        .getOrCreate()
    )
    log.info("CDCIngestion(debezium) start. topic=%s starting=%s trigger=%s",
             DEBEZIUM_TOPIC, STARTING_OFFSETS, TRIGGER_INTERVAL)

    raw = spark.readStream.format("kafka").options(**kafka_read_options()).load()

    parsed = (
        raw
        .select(
            F.col("topic").alias("_kafka_topic"),
            F.col("partition").alias("_kafka_partition"),
            F.col("offset").alias("_kafka_offset"),
            F.col("timestamp").alias("_kafka_ts"),
            F.from_json(F.col("value").cast("string"), ENVELOPE_SCHEMA).alias("evt"),
        )
        .select("_kafka_topic", "_kafka_partition", "_kafka_offset",
                "_kafka_ts", "evt.*")
        .withColumn("ds", F.date_format(F.to_timestamp(F.col("ts_ms").cast("long") / 1000),
                                        "yyyy-MM-dd"))
        .withColumn("_pulse_processing_ts", F.lit(PULSE_PROCESSING_TS))
        .withColumn("_pulse_run_id", F.lit(RUN_ID))
        .withColumn("_pulse_pipeline", F.lit(PIPELINE_NAME))
        .withColumn("_pulse_source", F.lit(f"{SOR_SLUG}/{DEBEZIUM_TOPIC}"))
    )

    log.info("CDC stream sink format=%s output=%s checkpoint=%s",
             LAKE_FORMAT, TABLE_PATH, CHECKPOINT_PATH)

    if LAKE_FORMAT == "delta":
        query = (
            parsed.writeStream
            .format("delta")
            .option("checkpointLocation", CHECKPOINT_PATH)
            .partitionBy("ds")
            .outputMode("append")
            .trigger(processingTime=TRIGGER_INTERVAL)
            .start(TABLE_PATH)
        )
    elif LAKE_FORMAT == "iceberg_external":
        query = (
            parsed.writeStream
            .format("iceberg")
            .option("checkpointLocation", CHECKPOINT_PATH)
            .option("path", TABLE_PATH)
            .outputMode("append")
            .trigger(processingTime=TRIGGER_INTERVAL)
            .start()
        )
    else:
        raise ValueError(
            f"Unsupported LAKE_FORMAT for CDC streaming bronze: {LAKE_FORMAT}. "
            "Use delta or iceberg_external for streaming sinks."
        )

    query.awaitTermination()


if __name__ == "__main__":
    main()
