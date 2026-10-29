package io.github.imzmq.interview.chat.application.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCandidate;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCardinality;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCategory;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryQuarantineReason;
import io.github.imzmq.interview.chat.domain.memory.ChatMemorySourceQuote;
import io.github.imzmq.interview.chat.domain.memory.ChatMemorySubjectKey;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryEvolutionDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryEvolutionMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryLifecycleWriterTest {

    private static final LocalDateTime MESSAGE_TIME = LocalDateTime.of(2026, 10, 29, 9, 0);
    private static final LocalDateTime WRITE_TIME = MESSAGE_TIME.plusMinutes(5);

    private final ChatMemoryItemMapper itemMapper = mock(ChatMemoryItemMapper.class);
    private final ChatMemorySourceMapper sourceMapper = mock(ChatMemorySourceMapper.class);
    private final ChatMemoryEvolutionMapper evolutionMapper = mock(ChatMemoryEvolutionMapper.class);
    private final ChatMemoryOutboxEventService outboxEventService = mock(ChatMemoryOutboxEventService.class);
    private final ChatMemoryObservationPublisher observationPublisher = mock(ChatMemoryObservationPublisher.class);
    private final ChatMemoryLifecycleWriter writer = new ChatMemoryLifecycleWriter(
            itemMapper, sourceMapper, evolutionMapper, outboxEventService, observationPublisher);

    ChatMemoryLifecycleWriterTest() {
        when(sourceMapper.insert(any(ChatMemorySourceDO.class))).thenReturn(1);
        when(sourceMapper.updateById(any(ChatMemorySourceDO.class))).thenReturn(1);
        when(evolutionMapper.insert(any(ChatMemoryEvolutionDO.class))).thenReturn(1);
    }

    @Test
    void activateInsertsActiveItemSourceAndUpsert() {
        when(itemMapper.lockThemeRows("user-1", "FACT", "fact.framework"))
                .thenReturn(List.of());
        assignInsertedIds(100L);

        writer.writeBatch(List.of(request(
                ChatMemoryCategory.FACT, "fact.framework", "Spring", "hash-spring",
                ChatMemoryCardinality.MULTI, "message-1", 10L, MESSAGE_TIME, 3, 9)), WRITE_TIME);

        ArgumentCaptor<ChatMemoryItemDO> itemCaptor = ArgumentCaptor.forClass(ChatMemoryItemDO.class);
        verify(itemMapper).insert(itemCaptor.capture());
        ChatMemoryItemDO item = itemCaptor.getValue();
        assertThat(item.getUserId()).isEqualTo("user-1");
        assertThat(item.getCategory()).isEqualTo("FACT");
        assertThat(item.getSubjectKey()).isEqualTo("fact.framework");
        assertThat(item.getSubjectCardinality()).isEqualTo("MULTI");
        assertThat(item.getStatus()).isEqualTo("ACTIVE");
        assertThat(item.getValidFrom()).isEqualTo(MESSAGE_TIME);
        assertThat(item.getOriginMessageCreatedAt()).isEqualTo(MESSAGE_TIME);
        assertThat(item.getOriginMessagePk()).isEqualTo(10L);
        assertThat(item.getOriginQuoteStart()).isEqualTo(3);
        assertThat(item.getQuarantineReason()).isNull();
        assertThat(item.getVersion()).isEqualTo(1L);

        verify(sourceMapper).insert(any(ChatMemorySourceDO.class));
        verify(outboxEventService).enqueue(100L, "user-1", 1L, "UPSERT");
        verify(evolutionMapper, never()).insert(any());
    }

    @Test
    void attachSourceRestoresOrInsertsSourceThenBumpsVersionAndUpserts() {
        ChatMemoryItemDO current = activeItem(
                20L, "FACT", "fact.framework", "MULTI", "hash-spring", 4L,
                MESSAGE_TIME.minusDays(1), 1L, 0);
        when(itemMapper.lockThemeRows("user-1", "FACT", "fact.framework"))
                .thenReturn(List.of(current));
        when(sourceMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(itemMapper.bumpVersion(20L, "user-1", 4L, 5L)).thenReturn(1);

        writer.writeBatch(List.of(request(
                ChatMemoryCategory.FACT, "fact.framework", "Spring", "hash-spring",
                ChatMemoryCardinality.MULTI, "message-1", 10L, MESSAGE_TIME, 3, 9)), WRITE_TIME);

        var order = inOrder(sourceMapper, itemMapper, outboxEventService);
        order.verify(sourceMapper).insert(any(ChatMemorySourceDO.class));
        order.verify(itemMapper).bumpVersion(20L, "user-1", 4L, 5L);
        order.verify(outboxEventService).enqueue(20L, "user-1", 5L, "UPSERT");
        verify(itemMapper, never()).insert(any());
    }

    @Test
    void attachSourceRestoresInvalidatedExactSourceBeforeVersionBump() {
        ChatMemoryItemDO current = activeItem(
                20L, "FACT", "fact.framework", "MULTI", "hash-spring", 4L,
                MESSAGE_TIME.minusDays(1), 1L, 0);
        ChatMemorySourceDO source = new ChatMemorySourceDO();
        source.setId(30L);
        source.setInvalidatedAt(MESSAGE_TIME.minusHours(1));
        when(itemMapper.lockThemeRows("user-1", "FACT", "fact.framework"))
                .thenReturn(List.of(current));
        when(sourceMapper.selectOne(any(Wrapper.class))).thenReturn(source);
        when(itemMapper.bumpVersion(20L, "user-1", 4L, 5L)).thenReturn(1);

        writer.writeBatch(List.of(request(
                ChatMemoryCategory.FACT, "fact.framework", "Spring", "hash-spring",
                ChatMemoryCardinality.MULTI, "message-1", 10L, MESSAGE_TIME, 3, 9)), WRITE_TIME);

        var order = inOrder(sourceMapper, itemMapper, outboxEventService);
        order.verify(sourceMapper).updateById(source);
        order.verify(itemMapper).bumpVersion(20L, "user-1", 4L, 5L);
        order.verify(outboxEventService).enqueue(20L, "user-1", 5L, "UPSERT");
        assertThat(source.getInvalidatedAt()).isNull();
        verify(sourceMapper, never()).insert(any());
    }

    @Test
    void exactEffectiveSourceIsIdempotent() {
        ChatMemoryItemDO current = activeItem(
                20L, "FACT", "fact.framework", "MULTI", "hash-spring", 4L,
                MESSAGE_TIME.minusDays(1), 1L, 0);
        ChatMemorySourceDO source = new ChatMemorySourceDO();
        source.setId(30L);
        source.setInvalidatedAt(null);
        when(itemMapper.lockThemeRows("user-1", "FACT", "fact.framework"))
                .thenReturn(List.of(current));
        when(sourceMapper.selectOne(any(Wrapper.class))).thenReturn(source);

        writer.writeBatch(List.of(request(
                ChatMemoryCategory.FACT, "fact.framework", "Spring", "hash-spring",
                ChatMemoryCardinality.MULTI, "message-1", 10L, MESSAGE_TIME, 3, 9)), WRITE_TIME);

        verify(sourceMapper, never()).insert(any());
        verify(sourceMapper, never()).updateById(any());
        verify(itemMapper, never()).bumpVersion(any(Long.class), any(String.class), any(Long.class), any(Long.class));
        verify(outboxEventService, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void supersedeClosesOldItemCreatesRelationAndQueuesDeleteThenUpsert() {
        ChatMemoryItemDO current = activeItem(
                20L, "PREFERENCE", "preference.editor", "SINGLE", "hash-vim", 4L,
                MESSAGE_TIME.minusDays(1), 1L, 0);
        when(itemMapper.lockThemeRows("user-1", "PREFERENCE", "preference.editor"))
                .thenReturn(List.of(current));
        when(itemMapper.markSuperseded(20L, "user-1", 4L, 5L, MESSAGE_TIME)).thenReturn(1);
        assignInsertedIds(21L);

        writer.writeBatch(List.of(request(
                ChatMemoryCategory.PREFERENCE, "preference.editor", "IntelliJ", "hash-idea",
                ChatMemoryCardinality.SINGLE, "message-1", 10L, MESSAGE_TIME, 3, 9)), WRITE_TIME);

        var order = inOrder(itemMapper, evolutionMapper, outboxEventService);
        order.verify(itemMapper).markSuperseded(20L, "user-1", 4L, 5L, MESSAGE_TIME);
        order.verify(itemMapper).insert(any(ChatMemoryItemDO.class));
        order.verify(evolutionMapper).insert(any(ChatMemoryEvolutionDO.class));
        order.verify(outboxEventService).enqueue(20L, "user-1", 5L, "DELETE");
        order.verify(outboxEventService).enqueue(21L, "user-1", 1L, "UPSERT");
        verify(observationPublisher).lifecycleAfterCommit("AUTO_SUPERSEDED", Map.of(
                "stage", "write",
                "resultCount", 2,
                "previousStatus", "ACTIVE",
                "nextStatus", "ACTIVE"));

        ArgumentCaptor<ChatMemoryEvolutionDO> relationCaptor =
                ArgumentCaptor.forClass(ChatMemoryEvolutionDO.class);
        verify(evolutionMapper).insert(relationCaptor.capture());
        assertThat(relationCaptor.getValue()).satisfies(relation -> {
            assertThat(relation.getUserId()).isEqualTo("user-1");
            assertThat(relation.getFromMemoryId()).isEqualTo(21L);
            assertThat(relation.getToMemoryId()).isEqualTo(20L);
            assertThat(relation.getRelationType()).isEqualTo("SUPERSEDES");
        });
    }

    @Test
    void quarantinePersistsCandidateSourceAndConflictRelationsWithoutOutbox() {
        ChatMemoryItemDO current = activeItem(
                20L, "PREFERENCE", "preference.editor", "SINGLE", "hash-idea", 4L,
                MESSAGE_TIME.plusDays(1), 20L, 0);
        when(itemMapper.lockThemeRows("user-1", "PREFERENCE", "preference.editor"))
                .thenReturn(List.of(current));
        assignInsertedIds(22L);

        writer.writeBatch(List.of(request(
                ChatMemoryCategory.PREFERENCE, "preference.editor", "Vim", "hash-vim",
                ChatMemoryCardinality.SINGLE, "message-1", 10L, MESSAGE_TIME, 3, 9)), WRITE_TIME);

        ArgumentCaptor<ChatMemoryItemDO> itemCaptor = ArgumentCaptor.forClass(ChatMemoryItemDO.class);
        verify(itemMapper).insert(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getStatus()).isEqualTo("QUARANTINED");
        assertThat(itemCaptor.getValue().getQuarantineReason())
                .isEqualTo(ChatMemoryQuarantineReason.OLDER_THAN_CURRENT.name());
        verify(sourceMapper).insert(any(ChatMemorySourceDO.class));
        verify(evolutionMapper).insert(org.mockito.ArgumentMatchers.argThat(relation ->
                relation.getFromMemoryId().equals(22L)
                        && relation.getToMemoryId().equals(20L)
                        && "CONFLICTS_WITH".equals(relation.getRelationType())));
        verify(observationPublisher).lifecycleAfterCommit("QUARANTINED", Map.of(
                "stage", "write",
                "resultCount", 1,
                "reasonCode", ChatMemoryQuarantineReason.OLDER_THAN_CURRENT.name(),
                "nextStatus", "QUARANTINED"));
        verify(outboxEventService, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void singletonBatchAmbiguityIsDetectedBeforeWritingAndQuarantinesEveryCandidate() {
        when(itemMapper.lockThemeRows("user-1", "PREFERENCE", "preference.editor"))
                .thenReturn(List.of());
        assignInsertedIds(40L);

        writer.writeBatch(List.of(
                request(ChatMemoryCategory.PREFERENCE, "preference.editor", "Vim", "hash-vim",
                        ChatMemoryCardinality.SINGLE, "message-1", 10L, MESSAGE_TIME, 0, 3),
                request(ChatMemoryCategory.PREFERENCE, "preference.editor", "Emacs", "hash-emacs",
                        ChatMemoryCardinality.SINGLE, "message-2", 11L,
                        MESSAGE_TIME.plusSeconds(1), 0, 5)), WRITE_TIME);

        ArgumentCaptor<ChatMemoryItemDO> items = ArgumentCaptor.forClass(ChatMemoryItemDO.class);
        verify(itemMapper, org.mockito.Mockito.times(2)).insert(items.capture());
        assertThat(items.getAllValues())
                .extracting(ChatMemoryItemDO::getStatus)
                .containsOnly("QUARANTINED");
        assertThat(items.getAllValues())
                .extracting(ChatMemoryItemDO::getQuarantineReason)
                .containsOnly(ChatMemoryQuarantineReason.AMBIGUOUS_BATCH_ORDER.name());
        verify(outboxEventService, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void casConflictUsesStableErrorAndStopsBeforeNewItemWrite() {
        ChatMemoryItemDO current = activeItem(
                20L, "PREFERENCE", "preference.editor", "SINGLE", "hash-vim", 4L,
                MESSAGE_TIME.minusDays(1), 1L, 0);
        when(itemMapper.lockThemeRows("user-1", "PREFERENCE", "preference.editor"))
                .thenReturn(List.of(current));
        when(itemMapper.markSuperseded(20L, "user-1", 4L, 5L, MESSAGE_TIME)).thenReturn(0);

        assertThatThrownBy(() -> writer.writeBatch(List.of(request(
                ChatMemoryCategory.PREFERENCE, "preference.editor", "IntelliJ", "hash-idea",
                ChatMemoryCardinality.SINGLE, "message-1", 10L, MESSAGE_TIME, 3, 9)), WRITE_TIME))
                .isInstanceOf(ChatMemoryCommitException.class)
                .extracting("code")
                .isEqualTo("MEMORY_VERSION_CONFLICT");

        verify(itemMapper, never()).insert(any());
        verify(evolutionMapper, never()).insert(any());
        verify(outboxEventService, never()).enqueue(any(), any(), any(), any());
    }

    private ChatMemoryLifecycleRequest request(
            ChatMemoryCategory category,
            String subjectKey,
            String content,
            String contentHash,
            ChatMemoryCardinality cardinality,
            String messageId,
            long messagePk,
            LocalDateTime messageTime,
            int sourceStart,
            int sourceEnd) {
        ChatMemoryCandidate candidate = new ChatMemoryCandidate(
                category,
                ChatMemorySubjectKey.parse(category, subjectKey),
                content,
                new ChatMemorySourceQuote(messageId, sourceStart, sourceEnd, "quote"));
        return new ChatMemoryLifecycleRequest(
                "user-1", "session-1", candidate, contentHash, cardinality,
                messageTime, messagePk, sourceStart, false);
    }

    private ChatMemoryItemDO activeItem(
            long id,
            String category,
            String subjectKey,
            String cardinality,
            String contentHash,
            long version,
            LocalDateTime originTime,
            long originPk,
            int originStart) {
        ChatMemoryItemDO item = new ChatMemoryItemDO();
        item.setId(id);
        item.setUserId("user-1");
        item.setCategory(category);
        item.setSubjectKey(subjectKey);
        item.setSubjectCardinality(cardinality);
        item.setContentHash(contentHash);
        item.setStatus("ACTIVE");
        item.setVersion(version);
        item.setOriginMessageCreatedAt(originTime);
        item.setOriginMessagePk(originPk);
        item.setOriginQuoteStart(originStart);
        return item;
    }

    private void assignInsertedIds(long firstId) {
        AtomicLong nextId = new AtomicLong(firstId);
        when(itemMapper.insert(any(ChatMemoryItemDO.class))).thenAnswer(invocation -> {
            ChatMemoryItemDO item = invocation.getArgument(0);
            item.setId(nextId.getAndIncrement());
            return 1;
        });
    }
}
