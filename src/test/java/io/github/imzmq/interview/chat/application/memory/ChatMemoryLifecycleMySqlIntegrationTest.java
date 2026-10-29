package io.github.imzmq.interview.chat.application.memory;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCandidate;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCardinality;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCategory;
import io.github.imzmq.interview.chat.domain.memory.ChatMemorySourceQuote;
import io.github.imzmq.interview.chat.domain.memory.ChatMemorySubjectKey;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatMemoryLifecycleMySqlIntegrationTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 10, 29, 12, 0);

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private ChatMemoryLifecycleWriter writer;

    @BeforeAll
    void startDatabaseContext() {
        String jdbcUrl = System.getProperty("test.mysql.jdbc-url", "");
        assumeTrue(!jdbcUrl.isBlank(),
                "set -Dtest.mysql.jdbc-url to run the MySQL lifecycle integration test");
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
        context = new AnnotationConfigApplicationContext();
        context.register(
                TestDatabaseConfiguration.class,
                ChatMemoryLifecycleWriter.class,
                ChatMemoryOutboxEventService.class);
        context.refresh();

        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        writer = context.getBean(ChatMemoryLifecycleWriter.class);
    }

    @AfterAll
    void closeDatabaseContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void concurrentSingletonWritesLeaveOneActiveWinnerWithAValidPredecessor() throws Exception {
        Fixture fixture = createFixture();
        try {
            Source old = addMessage(fixture, "old-message", BASE_TIME, "old quote");
            write(request(fixture, old, ChatMemoryCategory.PREFERENCE,
                    "preference.response_language", "English", ChatMemoryCardinality.SINGLE));

            Source first = addMessage(
                    fixture, "first-message", BASE_TIME.plusMinutes(1), "first quote");
            Source winner = addMessage(
                    fixture, "winner-message", BASE_TIME.plusMinutes(2), "winner quote");
            runConcurrently(
                    request(fixture, first, ChatMemoryCategory.PREFERENCE,
                            "preference.response_language", "Chinese", ChatMemoryCardinality.SINGLE),
                    request(fixture, winner, ChatMemoryCategory.PREFERENCE,
                            "preference.response_language", "Japanese", ChatMemoryCardinality.SINGLE));

            assertThat(count(fixture, "status = 'ACTIVE' AND subject_key = ?",
                    "preference.response_language")).isEqualTo(1);
            Long winnerId = jdbc.queryForObject(
                    "SELECT id FROM t_chat_memory_item WHERE user_id = ? AND status = 'ACTIVE' "
                            + "AND subject_key = ? AND content = ?",
                    Long.class, fixture.userId(), "preference.response_language", "Japanese");
            assertThat(winnerId).isNotNull();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_chat_memory_evolution relation "
                            + "JOIN t_chat_memory_item predecessor ON predecessor.id = relation.to_memory_id "
                            + "WHERE relation.user_id = ? AND relation.from_memory_id = ? "
                            + "AND relation.relation_type = 'SUPERSEDES' "
                            + "AND predecessor.user_id = relation.user_id "
                            + "AND predecessor.status = 'SUPERSEDED'",
                    Long.class, fixture.userId(), winnerId)).isEqualTo(1);
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void relationInsertFailureRollsBackSupersessionAndNewTruth() {
        assertWriteFailureRollsBack("t_chat_memory_evolution");
    }

    @Test
    void outboxInsertFailureRollsBackSupersessionRelationAndNewTruth() {
        assertWriteFailureRollsBack("t_chat_memory_outbox");
    }

    @Test
    void repeatedHistoricalContentCreatesANewNodeAfterInterveningSupersession() {
        Fixture fixture = createFixture();
        try {
            Source first = addMessage(fixture, "first-a", BASE_TIME, "first a quote");
            write(request(fixture, first, ChatMemoryCategory.PREFERENCE,
                    "preference.editor", "Vim", ChatMemoryCardinality.SINGLE));
            Long originalId = activeId(fixture, "preference.editor");

            Source second = addMessage(
                    fixture, "middle-b", BASE_TIME.plusMinutes(1), "middle b quote");
            write(request(fixture, second, ChatMemoryCategory.PREFERENCE,
                    "preference.editor", "Emacs", ChatMemoryCardinality.SINGLE));

            Source third = addMessage(
                    fixture, "repeat-a", BASE_TIME.plusMinutes(2), "repeat a quote");
            write(request(fixture, third, ChatMemoryCategory.PREFERENCE,
                    "preference.editor", "Vim", ChatMemoryCardinality.SINGLE));

            Long repeatedId = activeId(fixture, "preference.editor");
            assertThat(repeatedId).isNotEqualTo(originalId);
            assertThat(jdbc.queryForObject(
                    "SELECT content FROM t_chat_memory_item WHERE id = ?",
                    String.class, repeatedId)).isEqualTo("Vim");
            assertThat(count(fixture, "subject_key = ?", "preference.editor")).isEqualTo(3);
            assertThat(count(fixture,
                    "subject_key = ? AND status = 'ACTIVE'", "preference.editor")).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_chat_memory_evolution WHERE user_id = ? "
                            + "AND relation_type = 'SUPERSEDES'",
                    Long.class, fixture.userId())).isEqualTo(2);
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void differentMultiValuedFactsCanCoexistAsActiveTruths() {
        Fixture fixture = createFixture();
        try {
            Source first = addMessage(fixture, "java", BASE_TIME, "java quote");
            Source second = addMessage(
                    fixture, "kotlin", BASE_TIME.plusMinutes(1), "kotlin quote");

            write(request(fixture, first, ChatMemoryCategory.FACT,
                    "fact.known_language", "Java", ChatMemoryCardinality.MULTI));
            write(request(fixture, second, ChatMemoryCategory.FACT,
                    "fact.known_language", "Kotlin", ChatMemoryCardinality.MULTI));

            assertThat(jdbc.queryForList(
                    "SELECT content FROM t_chat_memory_item WHERE user_id = ? "
                            + "AND subject_key = ? AND status = 'ACTIVE' ORDER BY content",
                    String.class, fixture.userId(), "fact.known_language"))
                    .containsExactly("Java", "Kotlin");
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void databaseRejectsDuplicateActiveContentForOneSubject() {
        Fixture fixture = createFixture();
        try {
            String hash = sha256("Java");
            insertActiveItem(fixture, "Java", hash);

            assertThatThrownBy(() -> insertActiveItem(fixture, "Java duplicate", hash))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(count(fixture,
                    "subject_key = ? AND status = 'ACTIVE'", "fact.known_language"))
                    .isEqualTo(1);
        } finally {
            cleanup(fixture);
        }
    }

    private void assertWriteFailureRollsBack(String failingTable) {
        Fixture fixture = createFixture();
        String trigger = "trg_memory_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Source old = addMessage(fixture, "old", BASE_TIME, "old quote");
            write(request(fixture, old, ChatMemoryCategory.PREFERENCE,
                    "preference.editor", "Vim", ChatMemoryCardinality.SINGLE));
            Long oldId = activeId(fixture, "preference.editor");
            Source replacement = addMessage(
                    fixture, "replacement", BASE_TIME.plusMinutes(1), "replacement quote");

            jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON " + failingTable
                    + " FOR EACH ROW BEGIN IF NEW.user_id = '" + fixture.userId() + "' THEN "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced lifecycle rollback'; "
                    + "END IF; END");
            try {
                assertThatThrownBy(() -> write(request(
                        fixture, replacement, ChatMemoryCategory.PREFERENCE,
                        "preference.editor", "Emacs", ChatMemoryCardinality.SINGLE)))
                        .isInstanceOf(RuntimeException.class);
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
            }

            assertThat(jdbc.queryForMap(
                    "SELECT status, version, valid_until FROM t_chat_memory_item WHERE id = ?",
                    oldId))
                    .containsEntry("status", "ACTIVE")
                    .containsEntry("version", 1L)
                    .containsEntry("valid_until", null);
            assertThat(count(fixture, "subject_key = ?", "preference.editor")).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_chat_memory_evolution WHERE user_id = ?",
                    Long.class, fixture.userId())).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_chat_memory_outbox WHERE user_id = ?",
                    Long.class, fixture.userId())).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
            cleanup(fixture);
        }
    }

    private void runConcurrently(
            ChatMemoryLifecycleRequest first,
            ChatMemoryLifecycleRequest second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = List.of(
                    executor.submit(() -> concurrentWrite(first, ready, start)),
                    executor.submit(() -> concurrentWrite(second, ready, start)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void concurrentWrite(
            ChatMemoryLifecycleRequest request,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent lifecycle writes did not start");
            }
            writeWithDeadlockRetry(request);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent lifecycle write interrupted", exception);
        }
    }

    private void writeWithDeadlockRetry(ChatMemoryLifecycleRequest request) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                write(request);
                return;
            } catch (PessimisticLockingFailureException exception) {
                if (attempt == 3) {
                    throw exception;
                }
            }
        }
    }

    private void write(ChatMemoryLifecycleRequest request) {
        transactions.executeWithoutResult(ignored ->
                writer.writeBatch(List.of(request), request.originMessageCreatedAt().plusSeconds(1)));
    }

    private ChatMemoryLifecycleRequest request(
            Fixture fixture,
            Source source,
            ChatMemoryCategory category,
            String subjectKey,
            String content,
            ChatMemoryCardinality cardinality) {
        ChatMemoryCandidate candidate = new ChatMemoryCandidate(
                category,
                ChatMemorySubjectKey.parse(category, subjectKey),
                content,
                new ChatMemorySourceQuote(
                        source.messageId(), 0, source.quote().length(), source.quote()));
        return new ChatMemoryLifecycleRequest(
                fixture.userId(), fixture.sessionId(), candidate, sha256(content), cardinality,
                source.createdAt(), source.messagePk(), 0, false);
    }

    private Fixture createFixture() {
        String suffix = UUID.randomUUID().toString();
        Fixture fixture = new Fixture("lifecycle-user-" + suffix, "lifecycle-session-" + suffix);
        jdbc.update("INSERT INTO t_chat_session "
                        + "(session_id, user_id, title, deleted) VALUES (?, ?, 'lifecycle-test', 0)",
                fixture.sessionId(), fixture.userId());
        return fixture;
    }

    private Source addMessage(
            Fixture fixture,
            String label,
            LocalDateTime createdAt,
            String quote) {
        String messageId = "lifecycle-message-" + label + "-" + UUID.randomUUID();
        jdbc.update("INSERT INTO t_chat_message "
                        + "(message_id, session_id, role, content_type, content, deleted, created_at) "
                        + "VALUES (?, ?, 'user', 'text', ?, 0, ?)",
                messageId, fixture.sessionId(), quote, createdAt);
        Long messagePk = jdbc.queryForObject(
                "SELECT id FROM t_chat_message WHERE message_id = ?", Long.class, messageId);
        return new Source(messageId, messagePk, createdAt, quote);
    }

    private Long activeId(Fixture fixture, String subjectKey) {
        return jdbc.queryForObject(
                "SELECT id FROM t_chat_memory_item WHERE user_id = ? AND subject_key = ? "
                        + "AND status = 'ACTIVE'",
                Long.class, fixture.userId(), subjectKey);
    }

    private long count(Fixture fixture, String predicate, Object... parameters) {
        Object[] args = new Object[parameters.length + 1];
        args[0] = fixture.userId();
        System.arraycopy(parameters, 0, args, 1, parameters.length);
        Long result = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_chat_memory_item WHERE user_id = ? AND " + predicate,
                Long.class, args);
        return result == null ? 0 : result;
    }

    private void insertActiveItem(Fixture fixture, String content, String contentHash) {
        jdbc.update("INSERT INTO t_chat_memory_item "
                        + "(user_id, category, subject_key, subject_cardinality, content, content_hash, "
                        + "status, version, valid_from, origin_message_created_at, origin_message_pk, "
                        + "origin_quote_start) VALUES (?, 'FACT', 'fact.known_language', 'MULTI', "
                        + "?, ?, 'ACTIVE', 1, ?, ?, 1, 0)",
                fixture.userId(), content, contentHash, BASE_TIME, BASE_TIME);
    }

    private void cleanup(Fixture fixture) {
        jdbc.update("DELETE FROM t_chat_memory_outbox WHERE user_id = ?", fixture.userId());
        jdbc.update("DELETE FROM t_chat_memory_evolution WHERE user_id = ?", fixture.userId());
        jdbc.update("DELETE FROM t_chat_memory_source WHERE user_id = ?", fixture.userId());
        jdbc.update("DELETE FROM t_chat_memory_item WHERE user_id = ?", fixture.userId());
        jdbc.update("DELETE FROM t_chat_message WHERE session_id = ?", fixture.sessionId());
        jdbc.update("DELETE FROM t_chat_session WHERE session_id = ? AND user_id = ?",
                fixture.sessionId(), fixture.userId());
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Fixture(String userId, String sessionId) {
    }

    private record Source(
            String messageId,
            Long messagePk,
            LocalDateTime createdAt,
            String quote) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan(basePackageClasses = ChatMemoryItemMapper.class)
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
    }
}
