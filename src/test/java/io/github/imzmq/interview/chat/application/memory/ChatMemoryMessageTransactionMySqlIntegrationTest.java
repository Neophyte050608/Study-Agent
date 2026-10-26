package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ChatMemoryMessageTransactionMySqlIntegrationTest {

    @Test
    void rollsBackMessageWhenTaskInsertViolatesUniqueSourceMessageConstraint() {
        String jdbcUrl = System.getProperty("test.mysql.jdbc-url", "");
        assumeTrue(!jdbcUrl.isBlank(),
                "set -Dtest.mysql.jdbc-url to run the MySQL transaction rollback test");
        String username = System.getProperty("test.mysql.username", "root");
        String password = System.getProperty("test.mysql.password", "");

        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .cleanDisabled(true)
                .load()
                .migrate();

        TestDatabaseConfiguration.jdbcUrl = jdbcUrl;
        TestDatabaseConfiguration.username = username;
        TestDatabaseConfiguration.password = password;

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(TestDatabaseConfiguration.class, ChatMemoryMessageTransactionService.class);
            context.refresh();

            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            String sessionId = "rollback-session-" + UUID.randomUUID();
            String userId = "rollback-user-" + UUID.randomUUID();
            String existingTaskId = UUID.randomUUID().toString();
            String forcedSourceMessageId = "rollback-source-" + UUID.randomUUID();
            String triggerName = "trg_chat_memory_rollback_" + UUID.randomUUID().toString().replace("-", "");

            jdbc.update("INSERT INTO t_chat_session "
                            + "(session_id, user_id, title, deleted) VALUES (?, ?, 'rollback-test', 0)",
                    sessionId, userId);
            jdbc.update("INSERT INTO t_chat_message "
                            + "(message_id, session_id, role, content_type, content, deleted) "
                            + "VALUES (?, ?, 'user', 'text', 'existing', 0)",
                    forcedSourceMessageId, sessionId);
            Long sourceMessagePk = jdbc.queryForObject(
                    "SELECT id FROM t_chat_message WHERE message_id = ?", Long.class,
                    forcedSourceMessageId);
            jdbc.update("INSERT INTO t_chat_memory_extraction_task "
                            + "(task_id, user_id, session_id, source_message_id, source_message_pk, "
                            + "status, attempt_count, next_attempt_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP(6))",
                    existingTaskId, userId, sessionId, forcedSourceMessageId, sourceMessagePk);

            long messagesBefore = messageCount(jdbc, sessionId);
            jdbc.execute("CREATE TRIGGER " + triggerName
                    + " BEFORE INSERT ON t_chat_memory_extraction_task FOR EACH ROW "
                    + "SET NEW.source_message_id = '" + forcedSourceMessageId + "'");
            try {
                ChatMemoryMessageTransactionService service =
                        context.getBean(ChatMemoryMessageTransactionService.class);

                assertThatThrownBy(() -> service.saveUserMessage(userId, sessionId, "must roll back"))
                        .isInstanceOf(DataIntegrityViolationException.class);

                assertThat(messageCount(jdbc, sessionId)).isEqualTo(messagesBefore);
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_chat_message WHERE session_id = ? AND content = ?",
                        Long.class, sessionId, "must roll back")).isZero();
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS " + triggerName);
                jdbc.update("DELETE FROM t_chat_memory_extraction_task WHERE task_id = ?", existingTaskId);
                jdbc.update("DELETE FROM t_chat_message WHERE session_id = ?", sessionId);
                jdbc.update("DELETE FROM t_chat_session WHERE session_id = ?", sessionId);
            }
        }
    }

    private long messageCount(JdbcTemplate jdbc, String sessionId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_chat_message WHERE session_id = ?", Long.class, sessionId);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan(basePackageClasses = {ChatSessionMapper.class, ChatMemoryExtractionTaskMapper.class})
    static class TestDatabaseConfiguration {
        private static String jdbcUrl;
        private static String username;
        private static String password;

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(jdbcUrl, username, password);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTransactionFactory(new SpringManagedTransactionFactory());
            return factory.getObject();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        ChatMemoryProperties chatMemoryProperties() {
            ChatMemoryProperties properties = new ChatMemoryProperties();
            properties.setEnabled(true);
            properties.setExtractionDelay(Duration.ZERO);
            return properties;
        }
    }
}
