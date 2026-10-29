package io.github.imzmq.interview.config.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FlywayMySqlMigrationIntegrationTest {

    @Test
    void migratesAnEmptyMySqlDatabaseToAStartupReadyState() throws Exception {
        String jdbcUrl = System.getProperty("test.mysql.jdbc-url", "");
        assumeTrue(!jdbcUrl.isBlank(), "set -Dtest.mysql.jdbc-url to run the MySQL migration test");
        String username = System.getProperty("test.mysql.username", "root");
        String password = System.getProperty("test.mysql.password", "");

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .cleanDisabled(true)
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(8);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            assertThat(singleLong(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_name = 't_chat_session'"))
                    .isEqualTo(1);
            assertThat(singleLong(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1"))
                    .isEqualTo(8);
            assertThat(singleLong(statement,
                    "SELECT COUNT(*) FROM t_menu_config WHERE menu_code IN ('DASHBOARD', 'CODING')"))
                    .isZero();
            assertThat(singleLong(statement,
                    "SELECT sort_order FROM t_menu_config WHERE menu_code = 'CHAT'"))
                    .isEqualTo(1);
            assertThat(singleLong(statement,
                    "SELECT COUNT(*) FROM t_prompt_template WHERE deleted = 0 AND name IN ("
                            + quoted(REQUIRED_PROMPTS) + ")"))
                    .isEqualTo(REQUIRED_PROMPTS.size());
            assertThat(singleLong(statement,
                    "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                            + "WHERE table_schema = DATABASE() AND table_name = 't_chat_memory_item' "
                            + "AND index_name IN ('uk_chat_memory_active_singleton', "
                            + "'uk_chat_memory_active_content')"))
                    .isEqualTo(2);
        }
    }

    @Test
    void adoptsAnExistingSchemaWithoutOverwritingCustomizedReferenceData() throws Exception {
        String jdbcUrl = System.getProperty("test.mysql.adoption-jdbc-url", "");
        assumeTrue(!jdbcUrl.isBlank(),
                "set -Dtest.mysql.adoption-jdbc-url to run the existing-schema adoption test");
        String username = System.getProperty("test.mysql.username", "root");
        String password = System.getProperty("test.mysql.password", "");

        Flyway schemaBootstrap = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .target("1")
                .cleanDisabled(true)
                .load();
        assertThat(schemaBootstrap.migrate().migrationsExecuted).isEqualTo(1);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE flyway_schema_history");
            statement.executeUpdate("INSERT INTO t_prompt_template "
                    + "(name, category, type, title, description, content, is_builtin, deleted) VALUES "
                    + "('intent-tree-classifier', 'intent', 'TASK', 'custom', 'custom', "
                    + "'USER_CUSTOMIZED_CONTENT', 1, 0)");
        }

        Flyway adoptingFlyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .target("5")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .cleanDisabled(true)
                .load();

        assertThat(adoptingFlyway.migrate().migrationsExecuted).isEqualTo(4);

        long memoryId;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO t_chat_session "
                    + "(session_id, user_id, title, deleted) VALUES "
                    + "('legacy-session', 'legacy-user', 'legacy', 0)");
            statement.executeUpdate("INSERT INTO t_chat_message "
                    + "(message_id, session_id, role, content, deleted, created_at) VALUES "
                    + "('legacy-message', 'legacy-session', 'user', 'legacy quote', 0, "
                    + "'2026-01-02 03:04:05')");
            long messagePk = singleLong(statement,
                    "SELECT id FROM t_chat_message WHERE message_id = 'legacy-message'");
            statement.executeUpdate("INSERT INTO t_chat_memory_item "
                    + "(user_id, category, content, content_hash, status, version, created_at, updated_at) "
                    + "VALUES ('legacy-user', 'FACT', 'legacy fact', "
                    + "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', "
                    + "'ACTIVE', 1, '2026-01-03 04:05:06', '2026-01-03 04:05:06')");
            memoryId = singleLong(statement, "SELECT LAST_INSERT_ID()");
            statement.executeUpdate("INSERT INTO t_chat_memory_source "
                    + "(memory_id, user_id, session_id, source_message_id, source_message_pk, "
                    + "source_start, source_end) VALUES (" + memoryId
                    + ", 'legacy-user', 'legacy-session', 'legacy-message', " + messagePk
                    + ", 2, 8)");
            statement.executeUpdate("UPDATE t_prompt_template "
                    + "SET content = 'USER_CUSTOM_MEMORY_PROMPT' "
                    + "WHERE name = 'chat-memory-extract-task'");
        }

        Flyway lifecycleUpgrade = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .cleanDisabled(true)
                .load();
        assertThat(lifecycleUpgrade.migrate().migrationsExecuted).isEqualTo(3);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            assertThat(singleLong(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1"))
                    .isEqualTo(8);
            assertThat(singleLong(statement,
                    "SELECT COUNT(*) FROM t_menu_config WHERE menu_code IN ('DASHBOARD', 'CODING')"))
                    .isZero();
            assertThat(singleLong(statement,
                    "SELECT sort_order FROM t_menu_config WHERE menu_code = 'CHAT'"))
                    .isEqualTo(1);
            assertThat(singleLong(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE type = 'BASELINE' AND version = '1' AND success = 1"))
                    .isEqualTo(1);
            assertThat(singleString(statement,
                    "SELECT content FROM t_prompt_template WHERE name = 'intent-tree-classifier'"))
                    .isEqualTo("USER_CUSTOMIZED_CONTENT");
            assertThat(singleString(statement,
                    "SELECT content FROM t_prompt_template WHERE name = 'chat-memory-extract-task'"))
                    .isEqualTo("USER_CUSTOM_MEMORY_PROMPT");
            assertThat(singleString(statement,
                    "SELECT subject_key FROM t_chat_memory_item WHERE id = " + memoryId))
                    .isEqualTo("fact.legacy_" + memoryId);
            assertThat(singleString(statement,
                    "SELECT subject_cardinality FROM t_chat_memory_item WHERE id = " + memoryId))
                    .isEqualTo("MULTI");
            assertThat(singleString(statement,
                    "SELECT DATE_FORMAT(origin_message_created_at, '%Y-%m-%d %H:%i:%s') "
                            + "FROM t_chat_memory_item WHERE id = " + memoryId))
                    .isEqualTo("2026-01-02 03:04:05");
            assertThat(singleLong(statement,
                    "SELECT origin_quote_start FROM t_chat_memory_item WHERE id = " + memoryId))
                    .isEqualTo(2);
            assertThat(singleLong(statement,
                    "SELECT COUNT(*) FROM t_prompt_template WHERE deleted = 0 AND name IN ("
                            + quoted(REQUIRED_PROMPTS) + ")"))
                    .isEqualTo(REQUIRED_PROMPTS.size());

            insertLifecycleMemory(statement, "singleton-a", "PREFERENCE",
                    "preference.response_language", "SINGLE", repeated('b'), "first");
            assertThatThrownBy(() -> insertLifecycleMemory(statement, "singleton-a", "PREFERENCE",
                    "preference.response_language", "SINGLE", repeated('c'), "second"))
                    .isInstanceOf(SQLException.class);

            insertLifecycleMemory(statement, "content-a", "FACT",
                    "fact.known_language", "MULTI", repeated('d'), "Java");
            assertThatThrownBy(() -> insertLifecycleMemory(statement, "content-a", "FACT",
                    "fact.known_language", "MULTI", repeated('d'), "Java duplicate"))
                    .isInstanceOf(SQLException.class);
        }
    }


    private void insertLifecycleMemory(Statement statement,
                                       String userId,
                                       String category,
                                       String subjectKey,
                                       String cardinality,
                                       String contentHash,
                                       String content) throws SQLException {
        statement.executeUpdate("INSERT INTO t_chat_memory_item "
                + "(user_id, category, subject_key, subject_cardinality, content, content_hash, "
                + "status, version, valid_from, origin_message_created_at, origin_message_pk, "
                + "origin_quote_start) VALUES ('" + userId + "', '" + category + "', '"
                + subjectKey + "', '" + cardinality + "', '" + content + "', '"
                + contentHash + "', 'ACTIVE', 1, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 1, 0)");
    }

    private String repeated(char value) {
        return String.valueOf(value).repeat(64);
    }

    private long singleLong(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private String singleString(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private String quoted(Set<String> values) {
        return values.stream().sorted().map(value -> "'" + value + "'")
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
    }

    private static final Set<String> REQUIRED_PROMPTS = Set.of(
            "router", "interviewer", "coding-coach", "knowledge-assistant", "context-compressor",
            "turn-analyzer-system", "knowledge-digest-system", "task-router", "intent-tree-classifier",
            "intent-clarification", "intent-slot-refine", "first-question", "evaluation", "final-report",
            "coding-intent", "coding-question", "coding-evaluation", "coding-next-question", "learning-plan",
            "knowledge-qa", "turn-analyzer-task", "knowledge-digest-task", "batch-quiz-question",
            "ollama-local-route", "chat-context-compress", "cross-session-memorize", "auto-dream",
            "chat-memory-extract-system", "chat-memory-extract-task"
    );
}
