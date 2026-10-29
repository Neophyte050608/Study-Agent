package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.infrastructure.milvus.ChatMemoryProjectionPort;
import io.github.imzmq.interview.chat.infrastructure.milvus.ChatMemoryVectorCandidate;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryRecallServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ChatMemoryProjectionPort projectionPort = mock(ChatMemoryProjectionPort.class);
    private final ChatMemoryItemMapper itemMapper = mock(ChatMemoryItemMapper.class);
    private ExecutorService executor;
    private ChatMemoryProperties properties;
    private ChatMemoryRecallService service;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        properties = new ChatMemoryProperties();
        properties.setEnabled(true);
        properties.setRecallTimeout(Duration.ofMillis(100));
        properties.setRecallCandidates(20);
        properties.setRecallLimit(6);
        properties.setFallbackScanLimit(50);
        service = new ChatMemoryRecallService(
                Optional.of(projectionPort), itemMapper, properties, executor,
                mock(ChatMemoryObservationPublisher.class), CLOCK);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void revalidatesVectorCandidatesAgainstTheExactMysqlOwner() {
        ChatMemoryVectorCandidate candidate = candidate(1L, 3L, "hash-1", "FACT", 0.9);
        when(projectionPort.search("user-a", "Java", 20, Duration.ofMillis(100)))
                .thenReturn(List.of(candidate));
        when(itemMapper.selectRecallItems("user-a", List.of(1L), NOW_UTC))
                .thenReturn(List.of(item(1L, "user-b", "FACT", "other user's memory", "hash-1", "ACTIVE", 3L)));
        when(itemMapper.selectRecentEffectiveItems("user-a", 50, NOW_UTC)).thenReturn(List.of());

        assertThat(service.recall("user-a", "Java")).isEmpty();

        verify(itemMapper).selectRecallItems("user-a", List.of(1L), NOW_UTC);
    }

    @Test
    void rejectsStaleVersionWrongHashWrongCategoryAndNonActiveRows() {
        List<ChatMemoryVectorCandidate> candidates = List.of(
                candidate(1L, 1L, "hash-1", "FACT", 0.9),
                candidate(2L, 2L, "stale-hash", "FACT", 0.8),
                candidate(3L, 3L, "hash-3", "GOAL", 0.7),
                candidate(4L, 4L, "hash-4", "FACT", 0.6),
                candidate(5L, 5L, "hash-5", "FACT", 0.5));
        when(projectionPort.search(any(), any(), anyInt(), any())).thenReturn(candidates);
        when(itemMapper.selectRecallItems(eq("user-a"), anyList(), eq(NOW_UTC))).thenReturn(List.of(
                item(1L, "user-a", "FACT", "stale version", "hash-1", "ACTIVE", 2L),
                item(2L, "user-a", "FACT", "wrong hash", "hash-2", "ACTIVE", 2L),
                item(3L, "user-a", "FACT", "wrong category", "hash-3", "ACTIVE", 3L),
                item(4L, "user-a", "FACT", "deleted", "hash-4", "DELETED", 4L),
                item(5L, "user-a", "FACT", "quarantined", "hash-5", "QUARANTINED", 5L)));
        when(itemMapper.selectRecentEffectiveItems("user-a", 50, NOW_UTC)).thenReturn(List.of());

        assertThat(service.recall("user-a", "query")).isEmpty();
    }

    @Test
    void rejectsFutureExpiredAndSupersededVectorCandidatesAfterMysqlRevalidation() {
        List<ChatMemoryVectorCandidate> candidates = List.of(
                candidate(6L, 1L, "hash-6", "FACT", 0.9),
                candidate(7L, 1L, "hash-7", "FACT", 0.8),
                candidate(8L, 1L, "hash-8", "FACT", 0.7));
        ChatMemoryItemDO future = item(
                6L, "user-a", "FACT", "future", "hash-6", "ACTIVE", 1L);
        future.setValidFrom(NOW_UTC.plusSeconds(1));
        ChatMemoryItemDO expired = item(
                7L, "user-a", "FACT", "expired", "hash-7", "ACTIVE", 1L);
        expired.setValidUntil(NOW_UTC);
        ChatMemoryItemDO superseded = item(
                8L, "user-a", "FACT", "superseded", "hash-8", "SUPERSEDED", 1L);
        when(projectionPort.search(any(), any(), anyInt(), any())).thenReturn(candidates);
        when(itemMapper.selectRecallItems("user-a", List.of(6L, 7L, 8L), NOW_UTC))
                .thenReturn(List.of(future, expired, superseded));
        when(itemMapper.selectRecentEffectiveItems("user-a", 50, NOW_UTC)).thenReturn(List.of());

        assertThat(service.recall("user-a", "query")).isEmpty();
    }

    @Test
    void neverReturnsRawVectorCandidateWhenItsSourceSessionOrMessageIsInvalid() {
        when(projectionPort.search(any(), any(), anyInt(), any()))
                .thenReturn(List.of(candidate(9L, 1L, "hash-9", "FACT", 0.99)));
        // The mapper intentionally returns no row when source/session/message validity fails.
        when(itemMapper.selectRecallItems("user-a", List.of(9L), NOW_UTC)).thenReturn(List.of());
        when(itemMapper.selectRecentEffectiveItems("user-a", 50, NOW_UTC)).thenReturn(List.of());

        assertThat(service.recall("user-a", "sensitive query")).isEmpty();
    }

    @Test
    void effectiveRecallSqlValidatesSourceSessionMessageAndUserOwnership() throws Exception {
        Method method = ChatMemoryItemMapper.class.getMethod(
                "selectRecallItems", String.class, List.class, LocalDateTime.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertThat(sql)
                .contains("item.user_id = #{userid}")
                .contains("item.status = 'active'")
                .contains("item.valid_from <= #{now}")
                .contains("item.valid_until is null or item.valid_until > #{now}")
                .contains("source.invalidated_at is null")
                .contains("source.user_id = item.user_id")
                .contains("session.user_id = item.user_id")
                .contains("session.deleted = 0")
                .contains("message.deleted = 0")
                .contains("message.role = 'user'")
                .contains("message.id = source.source_message_pk")
                .contains("message.message_id = source.source_message_id")
                .contains("message.session_id = source.session_id");
    }

    @Test
    void fallsBackToLexicalMysqlRecallWhenMilvusOrEmbeddingFails() {
        when(projectionPort.search(any(), any(), anyInt(), any()))
                .thenThrow(new ChatMemoryProjectionException("MEMORY_PROJECTION_EMBEDDING_FAILED"));
        ChatMemoryItemDO relevant = item(
                1L, "user-a", "FACT", "我正在学习 Java 并发编程", "h1", "ACTIVE", 1L);
        ChatMemoryItemDO irrelevantFact = item(
                2L, "user-a", "FACT", "我住在杭州", "h2", "ACTIVE", 1L);
        ChatMemoryItemDO stablePreference = item(
                3L, "user-a", "PREFERENCE", "偏好先看代码示例", "h3", "ACTIVE", 1L);
        when(itemMapper.selectRecentEffectiveItems("user-a", 50, NOW_UTC))
                .thenReturn(List.of(irrelevantFact, stablePreference, relevant));

        assertThat(service.recall("user-a", "Java 并发怎么学习"))
                .extracting(ChatMemoryRecallService.RecalledMemory::content)
                .containsExactly("我正在学习 Java 并发编程", "偏好先看代码示例");
    }

    @Test
    void fallsBackAfterVectorTimeoutWithoutClearingCallerInterruptionState() {
        properties.setRecallTimeout(Duration.ofMillis(20));
        when(projectionPort.search(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        });
        ChatMemoryItemDO preference = item(
                1L, "user-a", "PREFERENCE", "偏好简洁回答", "h1", "ACTIVE", 1L);
        when(itemMapper.selectRecentEffectiveItems("user-a", 50, NOW_UTC)).thenReturn(List.of(preference));

        assertThat(service.recall("user-a", "unrelated"))
                .extracting(ChatMemoryRecallService.RecalledMemory::content)
                .containsExactly("偏好简洁回答");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void preservesCallerInterruptionAndReturnsEmpty() {
        Thread.currentThread().interrupt();
        try {
            assertThat(service.recall("user-a", "query")).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(itemMapper, never()).selectRecentEffectiveItems(any(), anyInt(), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void returnsEmptyWhenMysqlRevalidationFailsEvenIfMilvusHasCandidates() {
        when(projectionPort.search(any(), any(), anyInt(), any()))
                .thenReturn(List.of(candidate(1L, 1L, "h1", "FACT", 0.9)));
        when(itemMapper.selectRecallItems("user-a", List.of(1L), NOW_UTC))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThat(service.recall("user-a", "query")).isEmpty();

        verify(itemMapper, never()).selectRecentEffectiveItems(any(), anyInt(), any());
    }

    @Test
    void fallbackReturnsEmptyWhenMysqlIsUnavailable() {
        when(projectionPort.search(any(), any(), anyInt(), any()))
                .thenThrow(new ChatMemoryProjectionException("MEMORY_PROJECTION_TIMEOUT"));
        when(itemMapper.selectRecentEffectiveItems("user-a", 50, NOW_UTC))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThat(service.recall("user-a", "query")).isEmpty();
    }

    @Test
    void returnsAtMostSixValidatedMemoriesInVectorOrder() {
        List<ChatMemoryVectorCandidate> candidates = java.util.stream.LongStream.rangeClosed(1, 8)
                .mapToObj(id -> candidate(id, 1L, "h" + id, "FACT", 1.0 - id / 100.0))
                .toList();
        List<ChatMemoryItemDO> rows = java.util.stream.LongStream.rangeClosed(1, 8)
                .mapToObj(id -> item(id, "user-a", "FACT", "memory-" + id, "h" + id, "ACTIVE", 1L))
                .toList();
        when(projectionPort.search("user-a", "query", 20, Duration.ofMillis(100)))
                .thenReturn(candidates);
        when(itemMapper.selectRecallItems(eq("user-a"), anyList(), eq(NOW_UTC))).thenReturn(rows);

        assertThat(service.recall("user-a", "query"))
                .extracting(ChatMemoryRecallService.RecalledMemory::content)
                .containsExactly("memory-1", "memory-2", "memory-3", "memory-4", "memory-5", "memory-6");
    }

    @Test
    void disabledOrBlankRecallDoesNotTouchExternalStores() {
        properties.setEnabled(false);
        assertThat(service.recall("user-a", "query")).isEmpty();
        properties.setEnabled(true);
        assertThat(service.recall("user-a", " ")).isEmpty();
        assertThat(service.recall(" ", "query")).isEmpty();

        verify(projectionPort, never()).search(any(), any(), anyInt(), any());
        verify(itemMapper, never()).selectRecentEffectiveItems(any(), anyInt(), any());
    }

    private ChatMemoryVectorCandidate candidate(
            long id, long version, String hash, String category, double score) {
        return new ChatMemoryVectorCandidate(
                id + ":" + version, id, version, hash, category, score);
    }

    private ChatMemoryItemDO item(
            long id,
            String userId,
            String category,
            String content,
            String hash,
            String status,
            long version) {
        ChatMemoryItemDO item = new ChatMemoryItemDO();
        item.setId(id);
        item.setUserId(userId);
        item.setCategory(category);
        item.setContent(content);
        item.setContentHash(hash);
        item.setStatus(status);
        item.setVersion(version);
        item.setValidFrom(NOW_UTC.minusDays(1));
        item.setValidUntil(null);
        item.setUpdatedAt(LocalDateTime.of(2026, 10, 26, 12, 0).plusMinutes(id));
        return item;
    }
}
