package io.github.imzmq.interview.config.database;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayDatabaseMigrationTest {

    private static final Set<String> REQUIRED_SYSTEM_TEMPLATES = Set.of(
            "router",
            "interviewer",
            "coding-coach",
            "knowledge-assistant",
            "context-compressor",
            "turn-analyzer-system",
            "knowledge-digest-system"
    );

    private static final Set<String> REQUIRED_TASK_TEMPLATES = Set.of(
            "task-router",
            "intent-tree-classifier",
            "intent-clarification",
            "intent-slot-refine",
            "first-question",
            "evaluation",
            "final-report",
            "coding-intent",
            "coding-question",
            "coding-evaluation",
            "coding-next-question",
            "learning-plan",
            "knowledge-qa",
            "turn-analyzer-task",
            "knowledge-digest-task",
            "batch-quiz-question",
            "ollama-local-route",
            "chat-context-compress",
            "cross-session-memorize",
            "auto-dream"
    );

    @Test
    void flywayIsAvailableAtRuntime() throws ClassNotFoundException {
        assertThat(Class.forName("org.flywaydb.core.Flyway")).isNotNull();
    }

    @Test
    void flywayConfigurationDoesNotStealDatasourcePoolSettings() throws IOException {
        PropertySource<?> properties = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();

        assertThat(properties.getProperty("spring.flyway.enabled")).isEqualTo(true);
        assertThat(properties.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo(true);
        assertThat(properties.getProperty("spring.flyway.baseline-version")).isEqualTo(1);
        assertThat(properties.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo(20);
        assertThat(properties.getProperty("spring.flyway.hikari.maximum-pool-size")).isNull();
    }

    @Test
    void versionedMigrationsCreateSchemaAndSeedEveryStartupRequiredPrompt() throws IOException {
        String schemaMigration = readResource("db/migration/V1__create_application_schema.sql");
        String referenceDataMigration = readResource("db/migration/V2__seed_reference_data.sql");

        assertThat(schemaMigration)
                .contains("CREATE TABLE IF NOT EXISTS `t_prompt_template`")
                .contains("CREATE TABLE IF NOT EXISTS `t_chat_session`")
                .doesNotContain("TINYINT(1)");

        for (String name : REQUIRED_SYSTEM_TEMPLATES) {
            assertThat(referenceDataMigration)
                    .as("SYSTEM prompt %s must be seeded", name)
                    .contains("('" + name + "',", "'SYSTEM'");
        }
        for (String name : REQUIRED_TASK_TEMPLATES) {
            assertThat(referenceDataMigration)
                    .as("TASK prompt %s must be seeded", name)
                    .contains("('" + name + "',", "'TASK'");
        }

        assertThat(referenceDataMigration)
                .as("adopting an existing database must preserve customized reference data")
                .doesNotContain("ON DUPLICATE KEY UPDATE")
                .doesNotContainPattern("(?m)^UPDATE\\s")
                .contains("INSERT IGNORE INTO `t_prompt_template`")
                .containsOnlyOnce("('CHAT',");
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
