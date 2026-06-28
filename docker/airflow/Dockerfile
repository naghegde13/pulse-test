FROM apache/airflow:2.10.4-python3.11

USER root

RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends openjdk-17-jre-headless \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/java \
    && ln -s "$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")" /opt/java/openjdk

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/home/airflow/.local/bin:${JAVA_HOME}/bin:${PATH}"

USER airflow

RUN pip install --no-cache-dir \
        dbt-core \
        "dbt-spark[session]" \
        great-expectations \
        "pyspark==3.5.6" \
        psycopg2-binary \
    && pip install --no-cache-dir --no-deps apache-airflow-providers-apache-spark==4.11.3

COPY --chown=airflow:0 backend/src/main/resources/runtime-helpers/pulse_dates /opt/pulse_dates
RUN pip install --no-cache-dir --no-deps -e /opt/pulse_dates
