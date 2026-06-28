# PULSE codegen example: StreamWriter — publishes a gold or silver model
# to a Kafka topic for downstream stream consumers.
#
# What this blueprint does (and what it does NOT):
#   - Two source modes:
#       'batch_publish'    — read today's partition from Delta, send
#                            once. Use for daily reports/digests.
#       'streaming_publish' — Spark Structured Streaming readStream
#                            from a Delta CDF (change-data-feed) source,
#                            forward to Kafka in micro-batches. Use for
#                            event-driven downstream consumers.
#   - Key strategy: KEY_COLUMNS_LIST is hashed via SHA256 to produce
#     a deterministic Kafka key — guarantees same business-key rows
#     land on the same partition for ordering. The agent SHOULD set
#     this even when single-partition usage is expected; a future
#     scaling decision becomes painful otherwise.
#   - Schema strategy:
#       'json_envelope'    — flat JSON, fastest, least introspectable
#       'avro_schema_registry' — registers schema with Confluent SR;
#                            gives downstream consumers schema evolution
#       'protobuf'         — for very-high-throughput pipelines where
#                            payload size matters
#   - Does NOT do exactly-once. Kafka idempotent producer + transactional
#     send would give that, but it requires a producer epoch protocol
#     beyond this template. For most uses, at-least-once + idempotent
#     downstream is sufficient.

from datetime import date
import logging
import os

from pyspark.sql import SparkSession
from pyspark.sql import functions as F

from pulse_pipeline_state import advance_high_water_mark


RUN_ID = os.environ["PULSE_RUN_ID"]
PULSE_BUSINESS_DATE = os.environ.get("PULSE_BUSINESS_DATE", date.today().isoformat())
AS_OF_DATE = date.fromisoformat(PULSE_BUSINESS_DATE)


PIPELINE_NAME = "__PIPELINE_NAME__"
TENANT_SLUG = "__TENANT_SLUG__"
DOMAIN_SLUG = "__DOMAIN_SLUG__"

# Source gold-layer placeholders (format-branched read).
LAKE_FORMAT = "__LAKE_FORMAT__"
STORAGE_BACKEND = "__STORAGE_BACKEND__"
TABLE_PATH = "__TABLE_PATH__"
BQ_TABLE = "__BQ_TABLE__"

KAFKA_TOPIC = "__KAFKA_TOPIC__"
PUBLISH_MODE = "__PUBLISH_MODE__"           # 'batch_publish' | 'streaming_publish'
SCHEMA_STRATEGY = "__SCHEMA_STRATEGY__"     # 'json_envelope' | 'avro_schema_registry' | 'protobuf'
KEY_COLUMNS = __KEY_COLUMNS_LIST__          # ['order_id'] or composite
PARTITION_COLUMN = "__PARTITION_COLUMN__"
CHECKPOINT_BUCKET = "__CHECKPOINT_BUCKET__"


KAFKA_BOOTSTRAP = os.environ["__DEST_NAME___BOOTSTRAP_SERVERS"]
KAFKA_USERNAME = os.environ.get("__DEST_NAME___KAFKA_USERNAME")
KAFKA_PASSWORD = os.environ.get("__DEST_NAME___KAFKA_PASSWORD")
SCHEMA_REGISTRY_URL = os.environ.get("__DEST_NAME___SCHEMA_REGISTRY_URL")


log = logging.getLogger("pulse.sink.stream_kafka")
log.setLevel(logging.INFO)


def kafka_options():
    options = {
        "kafka.bootstrap.servers": KAFKA_BOOTSTRAP,
        "topic": KAFKA_TOPIC,
        # Producer idempotence is cheap and prevents duplicate writes
        # on Spark task retries. NOT the same as exactly-once.
        "kafka.enable.idempotence": "true",
        "kafka.acks": "all",
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


def encode_key(df, columns):
    """Build a Kafka key from one or more business-key columns.

    Hash the concatenation so the key is deterministic + bounded in
    length even when the business key is a wide string.
    """
    key_expr = F.concat_ws("|", *[F.col(c).cast("string") for c in columns])
    return F.sha2(key_expr, 256).cast("string")


def encode_value(df):
    if SCHEMA_STRATEGY == "json_envelope":
        return F.to_json(F.struct(*df.columns))
    # Avro / protobuf paths require external converters — codegen
    # substitutes the right encoding step at codegen time.
    raise NotImplementedError(
        f"Schema strategy {SCHEMA_STRATEGY} requires a paired registry/encoder; "
        "codegen should have emitted the variant for this strategy."
    )


def main():
    spark = (
        SparkSession.builder
        .appName(f"pulse-sink-kafka-{PIPELINE_NAME}-{RUN_ID}")
        .getOrCreate()
    )

    def read_source_filtered():
        """Format-branched batch read of today's gold partition."""
        if LAKE_FORMAT == "delta":
            return (spark.read.format("delta").load(TABLE_PATH)
                    .filter(f"{PARTITION_COLUMN} = '{AS_OF_DATE.isoformat()}'"))
        if LAKE_FORMAT in ("iceberg_external", "iceberg_bq_managed"):
            return (spark.read.format("iceberg").load(TABLE_PATH)
                    .filter(f"{PARTITION_COLUMN} = '{AS_OF_DATE.isoformat()}'"))
        if LAKE_FORMAT == "parquet":
            return (spark.read.format("parquet").load(TABLE_PATH)
                    .filter(f"{PARTITION_COLUMN} = '{AS_OF_DATE.isoformat()}'"))
        if LAKE_FORMAT == "bq_native":
            return (spark.read.format("bigquery").option("table", BQ_TABLE).load()
                    .filter(f"{PARTITION_COLUMN} = '{AS_OF_DATE.isoformat()}'"))
        raise ValueError(f"Unsupported source LAKE_FORMAT: {LAKE_FORMAT}")

    if PUBLISH_MODE == "batch_publish":
        df = read_source_filtered()
        keyed = df.select(
            encode_key(df, KEY_COLUMNS).alias("key"),
            encode_value(df).alias("value"),
        )
        keyed = keyed.cache()
        row_count = keyed.count()
        log.info("StreamWriter(batch) publishing rows=%d to topic=%s",
                 row_count, KAFKA_TOPIC)
        keyed.write.format("kafka").options(**kafka_options()).save()
        keyed.unpersist()

        advance_high_water_mark(
            pipeline=PIPELINE_NAME, run_id=RUN_ID,
            high_water_mark=AS_OF_DATE.isoformat(), row_count=row_count,
        )
        log.info("StreamWriter(batch) complete. topic=%s rows=%d",
                 KAFKA_TOPIC, row_count)

    elif PUBLISH_MODE == "streaming_publish":
        # Read Delta change-data-feed so we forward only new commits.
        # Requires the source table to have CDF enabled at create time.
        stream = (
            spark.readStream
            .format("delta")
            .option("readChangeFeed", "true")
            .load(source_path)
            # Only the committed inserts/updates — drop the prior-image
            # rows of an UPDATE since they're not "events".
            .filter(F.col("_change_type").isin("insert", "update_postimage"))
        )
        keyed = stream.select(
            encode_key(stream, KEY_COLUMNS).alias("key"),
            encode_value(stream).alias("value"),
        )

        checkpoint_path = (
            f"s3a://{CHECKPOINT_BUCKET}/{PIPELINE_NAME}/{KAFKA_TOPIC}_chkpt"
        )
        log.info("StreamWriter(stream) start. topic=%s checkpoint=%s",
                 KAFKA_TOPIC, checkpoint_path)
        query = (
            keyed.writeStream
            .format("kafka")
            .options(**kafka_options())
            .option("checkpointLocation", checkpoint_path)
            .outputMode("append")
            .start()
        )
        query.awaitTermination()

    else:
        raise ValueError(f"Unsupported publish_mode: {PUBLISH_MODE}")

    spark.stop()


if __name__ == "__main__":
    main()
