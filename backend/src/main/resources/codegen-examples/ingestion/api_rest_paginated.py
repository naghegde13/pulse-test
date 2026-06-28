# PULSE codegen example: ApiIngestion — paginated REST API → bronze.
#
# What this blueprint does (and what it does NOT):
#   - Pulls from a JSON REST endpoint with cursor or page-number
#     pagination, batches in-memory pages into Spark, and lands them
#     in bronze with audit columns. No transform — that's silver's job.
#   - Retries on transient errors (5xx, network timeouts) with
#     exponential backoff, gives up after RETRY_COUNT total attempts.
#   - Time-bounded per request via TIMEOUT_SECONDS so a hung server
#     doesn't pin a Spark job for hours.
#   - Bronze write is overwrite-mode keyed on _pulse_business_date so
#     same-day re-runs are idempotent. Streaming/append semantics
#     belong to StreamIngestion (Kafka).
#   - Auth resolution is via auth_credential_ref → secret manager. The
#     codegen-time placeholder `__SOURCE_NAME__` prefixes the runtime
#     env var the Airflow connection layer exposes; never inline
#     plaintext credentials.
#
# Convention notes:
#   - __PLACEHOLDER__ tokens are codegen-time. PULSE_* env vars runtime-only.
#   - Bronze layout: s3a://{tenant}_{domain}/{sor}/{pipeline}/SRC/{table}.

from datetime import date
import logging
import os
import time

import requests
from pyspark.sql import Row, SparkSession
from pyspark.sql import functions as F

from pulse_pipeline_state import advance_high_water_mark


RUN_ID = os.environ["PULSE_RUN_ID"]
PULSE_BUSINESS_DATE = os.environ.get("PULSE_BUSINESS_DATE", date.today().isoformat())
PULSE_PROCESSING_TS = os.environ.get("PULSE_PROCESSING_TS", "{{ ts }}")
AS_OF_DATE = date.fromisoformat(PULSE_BUSINESS_DATE)


PIPELINE_NAME = "__PIPELINE_NAME__"
PIPELINE_SLUG = "__PIPELINE_SLUG__"
TENANT_SLUG = "__TENANT_SLUG__"
DOMAIN_SLUG = "__DOMAIN_SLUG__"
SOR_SLUG = "__SOR_SLUG__"
TABLE_SLUG = "__TABLE_SLUG__"

# Storage convention (#30) — resolved by StoragePlaceholderResolver from
# the SubPipelineInstance's storage_backend, lake_layer, lake_format.
# ApiIngestion lands at bronze; bq_native is gold-only so this branch
# table excludes it.
LAKE_FORMAT = "__LAKE_FORMAT__"        # 'delta' | 'iceberg_external' | 'iceberg_bq_managed' | 'parquet'
STORAGE_BACKEND = "__STORAGE_BACKEND__"
TABLE_PATH = "__TABLE_PATH__"          # object-store URI when format is path-based
BQ_TABLE = "__BQ_TABLE__"              # populated only for iceberg_bq_managed where the connector accepts a table ref

# V93 ApiIngestion params.
BASE_URL = "__BASE_URL__"
ENDPOINT_PATH = "__ENDPOINT_PATH__"           # '/v2/orders'
PAGE_PARAM = "__PAGE_PARAM__"                 # 'page' | 'cursor' | 'after'
PAGE_SIZE = __PAGE_SIZE__
RETRY_COUNT = __RETRY_COUNT__                 # default 3
TIMEOUT_SECONDS = __TIMEOUT_SECONDS__         # per-request

# Auth credential reference. Resolved by the Airflow connection layer
# into env vars at runtime — never literal credentials in this file.
AUTH_KIND = "__AUTH_KIND__"                   # 'bearer' | 'api_key' | 'oauth2_client_credentials'
SECRET_TOKEN = os.environ["__SOURCE_NAME___API_TOKEN"]


log = logging.getLogger("pulse.api_ingestion")
log.setLevel(logging.INFO)


def auth_headers():
    if AUTH_KIND == "bearer":
        return {"Authorization": f"Bearer {SECRET_TOKEN}"}
    if AUTH_KIND == "api_key":
        return {"X-API-Key": SECRET_TOKEN}
    if AUTH_KIND == "oauth2_client_credentials":
        # OAuth flow: trade client creds for a short-lived access token.
        # Cached for the lifetime of this job — refresh-on-401 handled
        # in fetch_with_retry below.
        token = _get_oauth_token()
        return {"Authorization": f"Bearer {token}"}
    raise ValueError(f"Unsupported auth_kind: {AUTH_KIND}")


def _get_oauth_token():
    client_id = os.environ["__SOURCE_NAME___CLIENT_ID"]
    client_secret = os.environ["__SOURCE_NAME___CLIENT_SECRET"]
    token_url = os.environ["__SOURCE_NAME___TOKEN_URL"]
    response = requests.post(
        token_url,
        data={"grant_type": "client_credentials",
              "client_id": client_id, "client_secret": client_secret},
        timeout=TIMEOUT_SECONDS,
    )
    response.raise_for_status()
    return response.json()["access_token"]


def fetch_with_retry(url, params):
    """Issue one paginated GET with bounded retry/backoff.

    Retries 5xx and connection errors. 4xx (auth/auth/permission) is
    fatal — no point retrying a malformed request.
    """
    backoff_s = 1.0
    last_exc = None
    for attempt in range(1, RETRY_COUNT + 1):
        try:
            response = requests.get(
                url, params=params, headers=auth_headers(),
                timeout=TIMEOUT_SECONDS,
            )
            if response.status_code < 400:
                return response.json()
            if response.status_code == 401 and AUTH_KIND == "oauth2_client_credentials":
                # Token may have expired — caller will retry once with
                # a refreshed token by re-invoking auth_headers().
                log.info("401 received; refreshing OAuth token (attempt %d)", attempt)
                _get_oauth_token()
                continue
            if 400 <= response.status_code < 500:
                response.raise_for_status()
            log.warning("HTTP %d on attempt %d; retrying after %.1fs",
                        response.status_code, attempt, backoff_s)
        except (requests.ConnectionError, requests.Timeout) as exc:
            last_exc = exc
            log.warning("connection error on attempt %d: %s", attempt, exc)
        time.sleep(backoff_s)
        backoff_s *= 2
    raise RuntimeError(
        f"ApiIngestion exhausted {RETRY_COUNT} retries against {url}: {last_exc}"
    )


def page_iter():
    """Yield one parsed JSON page at a time.

    Two pagination flavors handled:
      - PAGE_PARAM='page'   → integer page numbers (1, 2, 3, ...).
      - PAGE_PARAM='cursor' → opaque next-cursor string from response.
    """
    url = f"{BASE_URL}{ENDPOINT_PATH}"
    if PAGE_PARAM == "page":
        page_num = 1
        while True:
            body = fetch_with_retry(url, {"page": page_num, "page_size": PAGE_SIZE})
            items = body if isinstance(body, list) else body.get("data") or body.get("items") or []
            if not items:
                return
            yield items
            page_num += 1
    else:  # cursor / after
        cursor = None
        while True:
            params = {"limit": PAGE_SIZE}
            if cursor:
                params[PAGE_PARAM] = cursor
            body = fetch_with_retry(url, params)
            items = body.get("data") or body.get("items") or []
            if not items:
                return
            yield items
            cursor = body.get("next_cursor") or body.get("after")
            if not cursor:
                return


def main():
    spark = (
        SparkSession.builder
        .appName(f"pulse-api-{PIPELINE_NAME}-{RUN_ID}")
        .getOrCreate()
    )
    log.info("ApiIngestion start. base=%s endpoint=%s page_param=%s size=%d backend=%s format=%s",
             BASE_URL, ENDPOINT_PATH, PAGE_PARAM, PAGE_SIZE, STORAGE_BACKEND, LAKE_FORMAT)

    # Materialize incrementally — one Spark write per page minimizes
    # JVM memory pressure when the API returns millions of items.
    total_rows = 0
    is_first_page = True
    replace_where = f"ds = '{AS_OF_DATE.isoformat()}'"
    for page in page_iter():
        rows = [Row(**rec) if isinstance(rec, dict) else Row(value=str(rec))
                for rec in page]
        page_df = (
            spark.createDataFrame(rows)
            .withColumn("ds", F.lit(AS_OF_DATE.isoformat()))
            .withColumn("_pulse_business_date", F.lit(AS_OF_DATE.isoformat()))
            .withColumn("_pulse_processing_ts", F.lit(PULSE_PROCESSING_TS))
            .withColumn("_pulse_run_id", F.lit(RUN_ID))
            .withColumn("_pulse_pipeline", F.lit(PIPELINE_NAME))
            .withColumn("_pulse_source", F.lit(f"{SOR_SLUG}/{ENDPOINT_PATH}"))
        )

        if LAKE_FORMAT == "delta":
            # First page overwrites today's ds partition; subsequent pages
            # append into it. replaceWhere on the first write makes the
            # whole multi-page operation idempotent on re-run.
            write_mode = "overwrite" if is_first_page else "append"
            writer = page_df.write.format("delta").mode(write_mode).partitionBy("ds")
            if is_first_page:
                writer = writer.option("replaceWhere", replace_where)
            writer.save(TABLE_PATH)
        elif LAKE_FORMAT == "iceberg_external":
            if is_first_page:
                page_df.sparkSession.sql(
                    f"DELETE FROM iceberg.`{TABLE_PATH}` WHERE {replace_where}"
                )
            page_df.writeTo(f"iceberg.`{TABLE_PATH}`").append()
        elif LAKE_FORMAT == "iceberg_bq_managed":
            (page_df.write
             .format("bigquery")
             .option("table", BQ_TABLE if BQ_TABLE else TABLE_PATH)
             .option("writeMethod", "indirect")
             .mode("append")
             .save())
        elif LAKE_FORMAT == "parquet":
            (page_df.write
             .format("parquet")
             .mode("overwrite" if is_first_page else "append")
             .option("partitionOverwriteMode", "dynamic")
             .partitionBy("ds")
             .save(TABLE_PATH))
        else:
            raise ValueError(
                f"Unsupported LAKE_FORMAT for ApiIngestion bronze: {LAKE_FORMAT}. "
                "Legal: delta | iceberg_external | iceberg_bq_managed | parquet."
            )

        total_rows += len(rows)
        is_first_page = False
        log.info("wrote page rows=%d cumulative=%d", len(rows), total_rows)

    advance_high_water_mark(
        pipeline=PIPELINE_NAME, run_id=RUN_ID,
        high_water_mark=AS_OF_DATE.isoformat(), row_count=total_rows,
    )
    log.info("ApiIngestion complete total_rows=%d format=%s", total_rows, LAKE_FORMAT)
    spark.stop()


if __name__ == "__main__":
    main()
