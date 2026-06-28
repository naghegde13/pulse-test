package com.pulse.chat.orchestration;

import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wires the LangGraph4j checkpointer that IS the snapshot/undo store (ADR 0025
 * §3) — the {@code langgraph4j-postgres-saver} {@link PostgresSaver}, keyed by
 * thread (session) + checkpoint (turn). No separate {@code chat_turn_snapshots}
 * table; the checkpointer owns its own Postgres tables.
 *
 * <p>The Postgres saver needs a REAL Postgres (its schema cannot be faithfully
 * exercised on H2), so production/default boot uses
 * {@code pulse.chat.checkpointer=postgres}. H2 fast-lane tests must opt into
 * {@code pulse.chat.checkpointer=memory}; the durable Postgres round-trip is
 * asserted in the postgres-it lane.</p>
 *
 * <p>NOTE on the 1.6.0-beta5 builder: it takes discrete {@code host/port/user/
 * password/database} (the {@code datasource(DataSource)} overload only exists on
 * the later 1.8.x train), so the JDBC URL is parsed to those parts.</p>
 */
@Configuration
public class CheckpointerConfig {

    private static final Logger log = LoggerFactory.getLogger(CheckpointerConfig.class);

    private static final Pattern JDBC_PG = Pattern.compile(
            "jdbc:postgresql://([^:/]+)(?::(\\d+))?/([^?]+).*");

    @Bean
    public BaseCheckpointSaver chatCheckpointSaver(
            @Value("${pulse.chat.checkpointer:postgres}") String kind,
            @Value("${spring.datasource.url:}") String jdbcUrl,
            @Value("${spring.datasource.username:}") String username,
            @Value("${spring.datasource.password:}") String password) {
        if ("postgres".equalsIgnoreCase(kind)) {
            try {
                Matcher m = JDBC_PG.matcher(jdbcUrl == null ? "" : jdbcUrl);
                if (!m.matches()) {
                    log.warn("pulse.chat.checkpointer=postgres but datasource URL '{}' is not a "
                            + "parseable jdbc:postgresql URL; falling back to MemorySaver.", jdbcUrl);
                    return new MemorySaver();
                }
                String host = m.group(1);
                int port = m.group(2) != null ? Integer.parseInt(m.group(2)) : 5432;
                String database = m.group(3);
                ensurePostgresSaverSchema(jdbcUrl, username, password);
                var serializer = new ObjectStreamStateSerializer<>(AgentState::new);
                PostgresSaver saver = PostgresSaver.builder()
                        .host(host)
                        .port(port)
                        .user(username)
                        .password(password)
                        .database(database)
                        .stateSerializer(serializer)
                        .createTables(false)
                        .build();
                log.info("Chat orchestration checkpointer: langgraph4j PostgresSaver @ {}:{}/{} (durable, resumable).",
                        host, port, database);
                return saver;
            } catch (Exception e) {
                log.warn("Failed to build PostgresSaver checkpointer ({}); falling back to MemorySaver.",
                        e.getMessage());
                return new MemorySaver();
            }
        }
        log.info("Chat orchestration checkpointer: in-memory MemorySaver (set pulse.chat.checkpointer=postgres for durable).");
        return new MemorySaver();
    }

    private void ensurePostgresSaverSchema(String jdbcUrl, String username, String password) throws SQLException {
        String[] statements = {
                """
                CREATE TABLE IF NOT EXISTS LG4JThread (
                    thread_id UUID PRIMARY KEY,
                    thread_name VARCHAR(255),
                    is_released BOOLEAN DEFAULT FALSE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS LG4JCheckpoint (
                    checkpoint_id UUID PRIMARY KEY,
                    parent_checkpoint_id UUID,
                    thread_id UUID NOT NULL,
                    node_id VARCHAR(255),
                    next_node_id VARCHAR(255),
                    state_data JSONB NOT NULL,
                    state_content_type VARCHAR(100) NOT NULL,
                    saved_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT fk_thread
                        FOREIGN KEY(thread_id)
                        REFERENCES LG4JThread(thread_id)
                        ON DELETE CASCADE
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_lg4jcheckpoint_thread_id ON LG4JCheckpoint(thread_id)",
                "CREATE INDEX IF NOT EXISTS idx_lg4jcheckpoint_thread_id_saved_at_desc ON LG4JCheckpoint(thread_id, saved_at DESC)",
                """
                CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_lg4jthread_thread_name_unreleased
                    ON LG4JThread(thread_name) WHERE is_released = FALSE
                """
        };
        try (var conn = DriverManager.getConnection(jdbcUrl, username, password);
             var stmt = conn.createStatement()) {
            for (String sql : statements) {
                stmt.execute(sql);
            }
        }
    }
}
