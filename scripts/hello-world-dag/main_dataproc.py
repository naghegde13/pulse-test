"""PySpark stub executed by Dataproc Serverless for the hello-world proof.

Invoked from ``main.py`` via DataprocCreateBatchOperator. argv[1] is the
Airflow run_id so the output Parquet path is unique per DAG run.
"""

from pyspark.sql import SparkSession
import sys

spark = SparkSession.builder.appName("pulse-hello-world-home-lending").getOrCreate()
print("hello world from pulse-home-lending tenant onboarding proof")

# Write a single Parquet row to gs://pulse-home-lending-dev-files/proof/hello-world-<run_id>.parquet
run_id = sys.argv[1] if len(sys.argv) > 1 else "manual"
df = spark.createDataFrame([("hello", "world", run_id)], ["greeting", "scope", "run_id"])
df.write.mode("overwrite").parquet(f"gs://pulse-home-lending-dev-files/proof/hello-world-{run_id}.parquet")

spark.stop()
