package io.github.imzmq.interview.config.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            assertThat(singleLong(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_name = 't_chat_session'"))
                    .isEqualTo(1);
            assertThat(singleLong(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1"))
                    .isEqualTo(4);
            assertThat(singleLong(statement,
                    "SELECT COUNT(*) FROM t_prompt_template WHERE deleted = 0 AND name IN ("
                            + quoted(REQUIRED_PROMPTS) + ")"))
                    .isEqualTo(REQUIRED_PROMPTS.size());
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
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .cleanDisabled(true)
                .load();

        assertThat(adoptingFlyway.migrate().migrationsExecuted).isEqualTo(3);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            assertThat(singleLong(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1"))
                    .isEqualTo(4);
            assertThat(singleLong(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE type = 'BASELINE' AND version = '1' AND success = 1"))
                    .isEqualTo(1);
            assertThat(singleString(statement,
                    "SELECT content FROM t_prompt_template WHERE name = 'intent-tree-classifier'"))
                    .isEqualTo("USER_CUSTOMIZED_CONTENT");
            assertThat(singleLong(statement,
                    "SELECT COUNT(*) FROM t_prompt_template WHERE deleted = 0 AND name IN ("
                            + quoted(REQUIRED_PROMPTS) + ")"))
                    .isEqualTo(REQUIRED_PROMPTS.size());
        }
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
