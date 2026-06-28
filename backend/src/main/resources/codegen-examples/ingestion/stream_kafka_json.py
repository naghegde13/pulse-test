# PULSE codegen example: StreamIngestion — Kafka JSON topic → bronze.
#
# What this blueprint does (and what it does NOT):
#   - Subscribes to a JSON-payload Kafka topic via Spark Structured
#     Streaming, lands raw events in bronze with audit columns. NO
#     transform, NO joins, NO state — just append.
#   - Schema is enforced at READ time via a JSON schema parameter,
#     because trusting Kafka producers to never silently change shape
#     is a recipe for silent data loss. Malformed records land in the
#     `_pulse_corrupt_record` column for downstream investigation.
#   - Different from CDCIngestion: CDC consumes Debezium-emitted change
#     events with op codes (c/u/d). StreamIngestion consumes domain
#     events directly (orders, clicks, IoT readings).
#   - Restartable — the Delta sink + Kafka offset checkpoint give
#     exactly-once semantics across job restarts. CHECKPOINT_BUCKET
#     paths must be unique per pipeline; sharing them silently corrupts
#     state.

from datetime import date
import json
import logging
import os

from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from pyspark.sql.types import StructType


RUN_ID = os.environ["PULSE_RUN_ID"]
PULSE_BUSINESS_DATE = os.environ.get("PULSE_BUSINESS_DATE", date.today().isoformat())
PULSE_PROCESSING_TS = os.environ.get("PULSE_PROCESSING_TS", "{{ ts }}")


PIPELINE_NAME = "__PIPELINE_NAME__"
TENANT_SLUG = "__TENANT_SLUG__"
DOMAIN_SLUG = "__DOMAIN_SLUG__"
SOR_SLUG = "__SOR_SLUG__"

KAFKA_TOPIC = "__KAFKA_TOPIC__"
KAFKA_GROUP_ID = "__KAFKA_GROUP_ID__"             # one per pipeline; never shared
STARTING_OFFSETS = "__STARTING_OFFSETS__"         # 'earliest' | 'latest' | '{"topic":{...}}'
TRIGGER_INTERVAL = "__TRIGGER_INTERVAL__"         # '30 seconds' | '5 minutes'
MAX_OFFSETS_PER_TRIGGER = __MAX_OFFSETS_PER_TRIGGER__

# JSON schema for the message payload — Spark StructType.fromJson form.
# The agent collects this from the user's contract / sample message.
PAYLOAD_SCHEMA_JSON = """__PAYLOAD_SCHEMA_JSON__"""

# Storage convention (#30).
LAKE_FORMAT = "__LAKE_FORMAT__"        # 'delta' | 'iceberg_external' (parquet/bq_managed less common for streaming)
STORAGE_BACKEND = "__STORAGE_BACKEND__"
TABLE_PATH = "__TABLE_PATH__"          # bronze stream sink table directory URI
BQ_TABLE = "__BQ_TABLE__"
CHECKPOINT_PATH = "__CHECKPOINT_PATH__"  # _checkpoints/<stream_name>/ — pipeline-scoped, MUST NOT be shared


KAFKA_BOOTSTRAP = os.environ["__SOURCE_NAME___BOOTSTRAP_SERVERS"]
KAFKA_USERNAME = os.environ.get("__SOURCE_NAME___KAFKA_USERNAME")
KAFKA_PASSWORD = os.environ.get("__SOURCE_NAME___KAFKA_PASSWORD")


log = logging.getLogger("pulse.stream_ingestion")
log.setLevel(logging.INFO)


def kafka_options():
    options = {
        "kafka.bootstrap.servers": KAFKA_BOOTSTRAP,
        "subscribe": KAFKA_TOPIC,
        "startingOffsets": STARTING_OFFSETS,
        "kafka.group.id": KAFKA_GROUP_ID,
        "maxOffsetsPerTrigger": str(MAX_OFFSETS_PER_TRIGGER),
        "failOnDataLoss": "false",
    }
    if KAFKA_USERNAME and KAFKA_PASSWORD:
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
        .appName(f"pulse-stream-{PIPELINE_NAME}-{RUN_ID}")
        .getOrCreate()
    )
    log.info("StreamIngestion start. topic=%s starting=%s trigger=%s",
             KAFKA_TOPIC, STARTING_OFFSETS, TRIGGER_INTERVAL)

    payload_schema = StructType.fromJson(json.loads(PAYLOAD_SCHEMA_JSON))

    raw = spark.readStream.format("kafka").options(**kafka_options()).load()

    parsed = (
        raw
        .select(
            F.col("topic").alias("_kafka_topic"),
            F.col("partition").alias("_kafka_partition"),
            F.col("offset").alias("_kafka_offset"),
            F.col("timestamp").alias("_kafka_ts"),
            F.col("key").cast("string").alias("_kafka_key"),
            F.from_json(F.col("value").cast("string"), payload_schema)
                .alias("payload"),
            # Hold onto the original payload text so corrupt records keep
            # their raw bytes for forensic inspection.
            F.col("value").cast("string").alias("_pulse_raw_payload"),
        )
        .withColumn("ds", F.date_format(F.col("_kafka_ts"), "yyyy-MM-dd"))
        .withColumn("_pulse_processing_ts", F.lit(PULSE_PROCESSING_TS))
        .withColumn("_pulse_run_id", F.lit(RUN_ID))
        .withColumn("_pulse_pipeline", F.lit(PIPELINE_NAME))
        .withColumn("_pulse_source", F.lit(f"{SOR_SLUG}/{KAFKA_TOPIC}"))
        .withColumn(
            "_pulse_corrupt_record",
            F.when(F.col("payload").isNull(), F.col("_pulse_raw_payload"))
             .otherwise(F.lit(None).cast("string")),
        )
    )

    log.info("StreamIngestion sink format=%s output=%s checkpoint=%s",
             LAKE_FORMAT, TABLE_PATH or BQ_TABLE, CHECKPOINT_PATH)

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
        # Streaming write to bq_managed iceberg / plain parquet is uncommon
        # and engine-version sensitive; codegen rejects rather than emit
        # something that fails at runtime.
        raise ValueError(
            f"Unsupported LAKE_FORMAT for streaming bronze: {LAKE_FORMAT}. "
            "Use delta or iceberg_external for streaming sinks."
        )

    query.awaitTermination()


if __name__ == "__main__":
    main()
