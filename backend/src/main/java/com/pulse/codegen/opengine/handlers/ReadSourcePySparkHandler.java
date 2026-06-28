package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

/**
 * PySpark emission for {@code read-source} (SPEC #2 §C.1).
 *
 * <p>Reads the configured source into the {@link EmitContext#dfVar() df} variable.
 * The source URI is resolved from {@code source_uri}, falling back to the
 * {@code PULSE_SOURCE_URI} env var at runtime; {@code source_format} defaults to
 * {@code csv}.
 *
 * <p><b>Mode (GCP vs DPC):</b> the read path is largely Mode-independent — both
 * read from object storage via the same Spark reader — so the fragment is the same
 * either way and the active Mode is noted in a comment (SPEC #2 §C.2). No Delta is
 * emitted (Delta is the format for neither Mode, C-2).
 *
 * <p><b>CDC / Kafka transport (GAP4):</b> the CDCIngestion connector supports both
 * JDBC (batch) and Kafka (stream). When {@code transport == "kafka"} a
 * {@code spark.readStream.format('kafka')} form is emitted; otherwise the batch
 * {@code spark.read.format(...)} form. Both fragments are deterministic.
 */
public final class ReadSourcePySparkHandler implements OpEmitHandler {

    private static final String DEFAULT_FORMAT = "csv";

    @Override
    public String opName() {
        return OpVocabulary.READ_SOURCE;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.PYSPARK;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String df = ctx.dfVar();
        String format = orDefault(config.getString("source_format"), DEFAULT_FORMAT);
        String uri = orDefault(config.getString("source_uri"), "");
        String transport = config.getString("transport");
        String sourceQuery = config.getString("source_query");
        String jdbcUrl = config.getString("jdbc_url");

        StringBuilder py = new StringBuilder();
        py.append("# read-source: PySpark read into '").append(df).append("'")
                .append(" (Mode=").append(ctx.mode()).append(").\n");
        py.append("import os\n");

        if (sourceQuery != null && !sourceQuery.isBlank()) {
            py.append("# SourceSQL: JDBC query read.\n");
            py.append("jdbc_url = os.environ.get('PULSE_SOURCE_JDBC_URL', ").append(pyStr(jdbcUrl)).append(")\n");
            py.append("jdbc_driver = os.environ.get('PULSE_SOURCE_JDBC_DRIVER', ")
                    .append(pyStr(orDefault(config.getString("jdbc_driver"), "org.postgresql.Driver"))).append(")\n");
            py.append("jdbc_user = os.environ.get('PULSE_SOURCE_JDBC_USER', ")
                    .append(pyStr(orDefault(config.getString("jdbc_user"), ""))).append(")\n");
            py.append("jdbc_password = os.environ.get('PULSE_SOURCE_JDBC_PASSWORD', ")
                    .append(pyStr(orDefault(config.getString("jdbc_password"), ""))).append(")\n");
            py.append(df).append(" = (spark.read\n");
            py.append("    .format('jdbc')\n");
            py.append("    .option('url', jdbc_url)\n");
            py.append("    .option('driver', jdbc_driver)\n");
            py.append("    .option('user', jdbc_user)\n");
            py.append("    .option('password', jdbc_password)\n");
            py.append("    .option('query', ").append(pyStr(sourceQuery)).append(")\n");
            py.append("    .load())\n");
            return py.toString();
        }

        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            String table = orDefault(config.getString("dbtable"), "${SOURCE_TABLE}");
            py.append("# JDBC source table read.\n");
            py.append(df).append(" = (spark.read\n");
            py.append("    .format('jdbc')\n");
            py.append("    .option('url', ").append(pyStr(jdbcUrl)).append(")\n");
            py.append("    .option('driver', ")
                    .append(pyStr(orDefault(config.getString("jdbc_driver"), "org.postgresql.Driver"))).append(")\n");
            py.append("    .option('user', ").append(pyStr(orDefault(config.getString("jdbc_user"), ""))).append(")\n");
            py.append("    .option('password', ").append(pyStr(orDefault(config.getString("jdbc_password"), ""))).append(")\n");
            py.append("    .option('dbtable', ").append(pyStr(table)).append(")\n");
            py.append("    .load())\n");
            return py.toString();
        }

        if ("kafka".equalsIgnoreCase(transport)) {
            // GAP4: CDCIngestion Kafka transport — streaming read.
            py.append("# CDC transport=kafka: streaming read (GAP4).\n");
            py.append(df).append(" = (spark.readStream.format('kafka')\n");
            py.append("    .option('kafka.bootstrap.servers', os.environ.get('PULSE_KAFKA_BROKERS', ''))\n");
            py.append("    .option('subscribe', os.environ.get('PULSE_KAFKA_TOPIC', ")
                    .append(pyStr(uri)).append("))\n");
            py.append("    .option('startingOffsets', 'latest')\n");
            py.append("    .load())\n");
            return py.toString();
        }

        // Batch read (default; also the JDBC/file path).
        py.append(df).append(" = (spark.read.format(").append(pyStr(format)).append(")\n");
        py.append("    .option('header', 'true')\n");
        py.append("    .load(os.environ.get('PULSE_SOURCE_URI', ").append(pyStr(uri)).append(")))\n");
        return py.toString();
    }

    private static String orDefault(String v, String dflt) {
        return (v == null || v.isBlank()) ? dflt : v;
    }

    /** Single-quoted Python string literal with minimal escaping (deterministic). */
    private static String pyStr(String v) {
        String s = v == null ? "" : v;
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
