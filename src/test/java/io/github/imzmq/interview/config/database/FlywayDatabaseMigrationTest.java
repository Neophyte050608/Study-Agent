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
    void chatMemoryIsBoundedAndDisabledByDefaultAndInLocalLite() throws IOException {
        PropertySource<?> defaults = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
        PropertySource<?> localLite = new YamlPropertySourceLoader()
                .load("application-local-lite", new ClassPathResource("application-local-lite.yml"))
                .getFirst();

        assertThat(defaults.getProperty("app.chat.memory.enabled")).isEqualTo(false);
        assertThat(defaults.getProperty("app.chat.memory.extraction-batch-size")).isEqualTo(8);
        assertThat(defaults.getProperty("app.chat.memory.collection-name")).isEqualTo("chat_memories");
        assertThat(defaults.getProperty("app.chat.memory.recall-limit")).isEqualTo(6);
        assertThat(localLite.getProperty("app.chat.memory.enabled")).isEqualTo(false);
    }

    @Test
    void versionedMigrationsCreateSchemaAndSeedEveryStartupRequiredPrompt() throws IOException {
        String schemaMigration = readResource("db/migration/V1__create_application_schema.sql");
        String referenceDataMigration = readResource("db/migration/V2__seed_reference_data.sql");
        String chatMemorySchema = readResource("db/migration/V3__create_chat_memory_schema.sql");
        String executions = readResource("db/migration/V5__create_agent_execution_schema.sql");
        assertThat(executions).contains("t_agent_execution_task", "t_agent_execution_node", "t_agent_execution_attempt", "lease_epoch", "ON DELETE CASCADE");
        String chatMemoryPrompts = readResource("db/migration/V4__seed_chat_memory_prompts.sql");
        String chatMemoryLifecycle = readResource("db/migration/V6__evolve_chat_memory_lifecycle.sql");
        String chatMemoryPromptUpgrade = readResource("db/migration/V7__upgrade_chat_memory_extraction_prompts.sql");

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


        assertThat(chatMemorySchema)
                .contains("t_chat_memory_item")
                .contains("t_chat_memory_source")
                .contains("t_chat_memory_extraction_task")
                .contains("t_chat_memory_outbox")
                .contains("COLLATE utf8mb4_bin")
                .contains("uk_chat_memory_content")
                .contains("uk_chat_memory_source")
                .contains("uk_chat_memory_task_message")
                .contains("uk_chat_memory_outbox_event")
                .contains("`user_id` VARCHAR(191)")
                .doesNotContain("t_user_chat_memory")
                .doesNotContain("ON DELETE CASCADE");

        assertThat(chatMemoryPrompts)
                .contains("chat-memory-extract-system")
                .contains("chat-memory-extract-task")
                .contains("INSERT IGNORE")
                .contains("sourceMessageId")
                .contains("quote")
                .doesNotContain("ON DUPLICATE KEY UPDATE");

        assertThat(chatMemoryLifecycle)
                .contains("subject_key", "subject_cardinality", "valid_from", "valid_until")
                .contains("origin_message_created_at", "origin_message_pk", "origin_quote_start")
                .contains("quarantine_reason", "active_singleton_marker", "active_content_marker")
                .contains("t_chat_memory_evolution", "SUPERSEDES", "CONFLICTS_WITH")
                .contains("DROP INDEX `uk_chat_memory_content`");

        assertThat(chatMemoryPromptUpgrade)
                .contains("subjectKey")
                .contains("WHERE `name` = 'chat-memory-extract-task'")
                .contains("AND `content` =")
                .doesNotContain("INSERT IGNORE")
                .doesNotContain("ON DUPLICATE KEY UPDATE");
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
