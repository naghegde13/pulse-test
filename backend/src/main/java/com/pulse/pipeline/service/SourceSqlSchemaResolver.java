package com.pulse.pipeline.service;

import com.pulse.common.text.DateMnemonic;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.ConnectorDefinition;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SourceSQL source-prepare collaborator. It resolves result-set metadata by
 * running the user source SELECT as a no-row subquery against the bound JDBC
 * connector. Connection failures are source-unreachable and fall back at the
 * caller; unsupported JDBC column types loud-fail because the schema would be
 * ambiguous.
 */
@Service
public class SourceSqlSchemaResolver {

    private static final Logger log = LoggerFactory.getLogger(SourceSqlSchemaResolver.class);
    private static final Pattern INLINE_MNEMONIC = Pattern.compile("\\[\\[\\s*([^\\]]+?)\\s*]]");
    private static final String DESIGN_TIME_MNEMONIC_DATE = "2026-01-01";

    private final ConnectorInstanceRepository connectorInstanceRepo;
    private final ConnectorDefinitionRepository connectorDefinitionRepo;
    private final CredentialProfileRepository credentialProfileRepo;

    public SourceSqlSchemaResolver(ConnectorInstanceRepository connectorInstanceRepo,
                                   ConnectorDefinitionRepository connectorDefinitionRepo,
                                   CredentialProfileRepository credentialProfileRepo) {
        this.connectorInstanceRepo = connectorInstanceRepo;
        this.connectorDefinitionRepo = connectorDefinitionRepo;
        this.credentialProfileRepo = credentialProfileRepo;
    }

    public Optional<List<Map<String, Object>>> resolveSourceColumns(SubPipelineInstance inst) {
        Map<String, Object> params = inst.getParams() == null ? Map.of() : inst.getParams();
        String sourceQuery = stringValue(params.get("source_query"));
        String connectorInstanceId = stringValue(params.get("connector_instance_id"));
        if (sourceQuery.isBlank() || connectorInstanceId.isBlank()) {
            return Optional.empty();
        }

        Optional<ConnectorInstance> connector = connectorInstanceRepo.findById(connectorInstanceId);
        Optional<CredentialProfile> credential =
                credentialProfileRepo.findByConnectorInstanceIdAndEnvironment(connectorInstanceId, "dev");
        if (connector.isEmpty() || credential.isEmpty()) {
            return Optional.empty();
        }

        ConnectorInstance connectorInstance = connector.get();
        Optional<ConnectorDefinition> connectorDefinition =
                connectorDefinitionRepo.findById(connectorInstance.getConnectorDefinitionId());

        Map<String, Object> config = credential.get().getConnectionMetadata();
        String jdbcUrl = jdbcUrl(connectorInstance, connectorDefinition.orElse(null), config);
        if (jdbcUrl.isBlank()) {
            return Optional.empty();
        }

        try (Connection connection = DriverManager.getConnection(
                jdbcUrl,
                stringValue(config.get("username")),
                stringValue(config.get("password")));
             Statement statement = connection.createStatement()) {
            statement.setMaxRows(0);
            String prepareSql = "SELECT * FROM (" + stripTrailingSemicolon(lowerDesignTimeMnemonics(sourceQuery))
                    + ") pulse_source_prepare WHERE 1=0";
            try (var rs = statement.executeQuery(prepareSql)) {
                ResultSetMetaData meta = rs.getMetaData();
                List<Map<String, Object>> columns = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    Map<String, Object> column = new LinkedHashMap<>();
                    String label = meta.getColumnLabel(i);
                    column.put("name", label == null || label.isBlank() ? meta.getColumnName(i) : label);
                    column.put("type", jdbcTypeToPulse(meta.getColumnType(i), meta.getColumnTypeName(i)));
                    column.put("nullable", meta.isNullable(i) != ResultSetMetaData.columnNoNulls);
                    column.put("lineage", "source");
                    columns.add(column);
                }
                return Optional.of(columns);
            }
        } catch (SQLException e) {
            log.warn("SourceSQL source-prepare unavailable for connector {}: SQLState={} message={}",
                    connectorInstanceId, e.getSQLState(), e.getMessage());
            return Optional.empty();
        }
    }

    String jdbcUrl(ConnectorInstance connector, ConnectorDefinition definition, Map<String, Object> config) {
        String explicit = firstNonBlank(config, "jdbc_url", "jdbcUrl", "url");
        if (!explicit.isBlank()) {
            return explicit;
        }
        String host = firstNonBlank(config, "host");
        String port = firstNonBlank(config, "port");
        String database = firstNonBlank(config, "database", "db");
        if (host.isBlank() || database.isBlank()) {
            return "";
        }
        String dialect = jdbcDialect(connector, definition);
        if ("postgres".equals(dialect)) {
            return "jdbc:postgresql://" + host + ":" + (port.isBlank() ? "5432" : port) + "/" + database;
        }
        if ("mysql".equals(dialect)) {
            return "jdbc:mysql://" + host + ":" + (port.isBlank() ? "3306" : port) + "/" + database;
        }
        if ("mssql".equals(dialect)) {
            return "jdbc:sqlserver://" + host + ":" + (port.isBlank() ? "1433" : port) + ";databaseName=" + database;
        }
        return "";
    }

    private String jdbcDialect(ConnectorInstance connector, ConnectorDefinition definition) {
        String definitionId = connector.getConnectorDefinitionId() == null
                ? ""
                : connector.getConnectorDefinitionId().toUpperCase(Locale.ROOT);
        String stableText = String.join(" ",
                definitionId,
                definition == null || definition.getName() == null ? "" : definition.getName(),
                definition == null || definition.getDockerRepository() == null ? "" : definition.getDockerRepository()
        ).toLowerCase(Locale.ROOT);

        if (stableText.contains("postgres")) return "postgres";
        if (stableText.contains("mysql")) return "mysql";
        if (stableText.contains("mssql") || stableText.contains("sql server")) return "mssql";
        return "";
    }

    private String jdbcTypeToPulse(int jdbcType, String typeName) {
        return switch (jdbcType) {
            case Types.CHAR, Types.NCHAR, Types.VARCHAR, Types.NVARCHAR, Types.LONGVARCHAR,
                 Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB, Types.SQLXML -> "string";
            case Types.SMALLINT, Types.TINYINT, Types.INTEGER -> "integer";
            case Types.BIGINT -> "long";
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> "double";
            case Types.NUMERIC, Types.DECIMAL -> "decimal";
            case Types.BOOLEAN, Types.BIT -> "boolean";
            case Types.DATE -> "date";
            case Types.TIME, Types.TIME_WITH_TIMEZONE,
                 Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "timestamp";
            default -> throw new IllegalArgumentException(
                    "Unsupported JDBC type for SourceSQL source-prepare: "
                            + jdbcType + (typeName == null ? "" : " (" + typeName + ")"));
        };
    }

    private String firstNonBlank(Map<String, Object> config, String... keys) {
        for (String key : keys) {
            String value = stringValue(config.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String stripTrailingSemicolon(String sql) {
        String out = sql.stripTrailing();
        while (out.endsWith(";")) {
            out = out.substring(0, out.length() - 1).stripTrailing();
        }
        return out;
    }

    String lowerDesignTimeMnemonics(String sql) {
        if (sql == null || !sql.contains("[[")) {
            return sql;
        }
        Matcher matcher = INLINE_MNEMONIC.matcher(sql);
        return matcher.replaceAll(result -> {
            String mnemonic = result.group(1).trim();
            DateMnemonic.validateOrThrow(mnemonic);
            return Matcher.quoteReplacement("DATE '" + DESIGN_TIME_MNEMONIC_DATE + "'");
        });
    }
}
