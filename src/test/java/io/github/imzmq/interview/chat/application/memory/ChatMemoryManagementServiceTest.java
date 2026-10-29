package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.application.contract.result.ChatMemoryRelationView;
import io.github.imzmq.interview.chat.application.contract.result.ChatMemoryView;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryEvolutionDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryEvolutionMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryManagementServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ChatMemoryItemMapper itemMapper = mock(ChatMemoryItemMapper.class);
    private final ChatMemorySourceMapper sourceMapper = mock(ChatMemorySourceMapper.class);
    private final ChatMemoryEvolutionMapper evolutionMapper = mock(ChatMemoryEvolutionMapper.class);
    private final ChatMemoryOutboxEventService outboxEventService = mock(ChatMemoryOutboxEventService.class);
    private final ChatMemoryManagementService service = new ChatMemoryManagementService(
            itemMapper, sourceMapper, evolutionMapper, outboxEventService, CLOCK);

    @Test
    void listsLifecycleFieldsWithValidatedStatusAndAccessibleSources() {
        ChatMemoryItemDO row = item(7L, "ACTIVE", "MULTI", 3L, NOW_UTC.minusDays(1), 10L, 0);
        row.setCategory("FACT");
        row.setContent("杭州");
        when(itemMapper.selectManagementPage("user-a", "ACTIVE", 99L, 100))
                .thenReturn(List.of(row));
        when(sourceMapper.selectAccessibleSources("user-a", List.of(7L)))
                .thenReturn(List.of(source(7L, "message-1")));

        List<ChatMemoryView> result = service.list("user-a", "ACTIVE", 500, 99L);

        assertThat(result).containsExactly(new ChatMemoryView(
                7L, "FACT", "fact.current_city", "MULTI", "杭州", "ACTIVE", null, 3L,
                row.getValidFrom().toInstant(ZoneOffset.UTC), null,
                row.getCreatedAt().toInstant(ZoneOffset.UTC),
                row.getUpdatedAt().toInstant(ZoneOffset.UTC), List.of("message-1")));
        verify(itemMapper).selectManagementPage("user-a", "ACTIVE", 99L, 100);
    }

    @Test
    void listRejectsUnknownStatusBeforeQueryingPersistence() {
        assertThatThrownBy(() -> service.list("user-a", "active", 20, null))
                .isInstanceOf(ChatMemoryManagementException.class)
                .extracting(exception -> ((ChatMemoryManagementException) exception).code())
                .isEqualTo("MEMORY_STATUS_INVALID");
        verify(itemMapper, never()).selectManagementPage(any(), any(), any(), any(Integer.class));
    }

    @Test
    void evolutionReturnsOnlyOwnerScopedSdkNeutralRelations() {
        ChatMemoryEvolutionDO relation = relation(8L, 7L, "CONFLICTS_WITH");
        when(evolutionMapper.selectOwnedRelations("user-a", 8L)).thenReturn(List.of(relation));

        assertThat(service.evolution("user-a", 8L)).containsExactly(
                new ChatMemoryRelationView(8L, 7L, "CONFLICTS_WITH", NOW));
        verify(evolutionMapper).selectOwnedRelations("user-a", 8L);
    }

    @Test
    void replacementSupersedesCurrentConflictThenActivatesCandidateAndQueuesConvergence() {
        ChatMemoryItemDO current = item(7L, "ACTIVE", "SINGLE", 4L,
                NOW_UTC.minusDays(2), 10L, 0);
        ChatMemoryItemDO candidate = item(8L, "QUARANTINED", "SINGLE", 3L,
                NOW_UTC.minusDays(1), 20L, 0);
        when(itemMapper.selectOwnedItem("user-a", 8L)).thenReturn(candidate);
        when(itemMapper.lockThemeRows("user-a", "PREFERENCE", "preference.editor"))
                .thenReturn(List.of(current, candidate));
        when(evolutionMapper.selectOwnedConflictTargets("user-a", 8L)).thenReturn(List.of(7L));
        when(itemMapper.markSuperseded(7L, "user-a", 4L, 5L, candidate.getValidFrom()))
                .thenReturn(1);
        when(itemMapper.activateQuarantined(8L, "user-a", 3L, 4L)).thenReturn(1);
        when(evolutionMapper.insert(any(ChatMemoryEvolutionDO.class))).thenReturn(1);

        service.confirmReplacement("user-a", 8L, 3L);

        var order = inOrder(itemMapper, evolutionMapper, outboxEventService);
        order.verify(itemMapper).markSuperseded(7L, "user-a", 4L, 5L, candidate.getValidFrom());
        order.verify(itemMapper).activateQuarantined(8L, "user-a", 3L, 4L);
        order.verify(evolutionMapper).insert(any(ChatMemoryEvolutionDO.class));
        order.verify(outboxEventService).enqueue(7L, "user-a", 5L, "DELETE");
        order.verify(outboxEventService).enqueue(8L, "user-a", 4L, "UPSERT");
        verify(evolutionMapper).insert(org.mockito.ArgumentMatchers.argThat(value ->
                value.getFromMemoryId().equals(8L) && value.getToMemoryId().equals(7L)
                        && "SUPERSEDES".equals(value.getRelationType())));
    }

    @Test
    void replacementRejectsOlderCandidateAndChangedConflictState() {
        ChatMemoryItemDO older = item(8L, "QUARANTINED", "SINGLE", 3L,
                NOW_UTC.minusDays(3), 5L, 0);
        older.setQuarantineReason("OLDER_THAN_CURRENT");
        when(itemMapper.selectOwnedItem("user-a", 8L)).thenReturn(older);
        when(itemMapper.lockThemeRows("user-a", "PREFERENCE", "preference.editor"))
                .thenReturn(List.of(older));

        assertCode(() -> service.confirmReplacement("user-a", 8L, 3L), "MEMORY_STATE_CONFLICT");
        verify(itemMapper, never()).activateQuarantined(any(Long.class), any(), any(Long.class), any(Long.class));
    }

    @Test
    void replacementRejectsConflictTargetThatIsNoLongerCurrent() {
        ChatMemoryItemDO changed = item(7L, "SUPERSEDED", "SINGLE", 5L,
                NOW_UTC.minusDays(2), 10L, 0);
        ChatMemoryItemDO candidate = item(8L, "QUARANTINED", "SINGLE", 3L,
                NOW_UTC.minusDays(1), 20L, 0);
        when(itemMapper.selectOwnedItem("user-a", 8L)).thenReturn(candidate);
        when(itemMapper.lockThemeRows("user-a", "PREFERENCE", "preference.editor"))
                .thenReturn(List.of(changed, candidate));
        when(evolutionMapper.selectOwnedConflictTargets("user-a", 8L)).thenReturn(List.of(7L));

        assertCode(() -> service.confirmReplacement("user-a", 8L, 3L),
                "MEMORY_STATE_CONFLICT");

        verify(itemMapper, never()).markSuperseded(
                any(Long.class), any(), any(Long.class), any(Long.class), any());
        verify(itemMapper, never()).activateQuarantined(
                any(Long.class), any(), any(Long.class), any(Long.class));
    }

    @Test
    void replacementRejectsAnUnexpectedCurrentThemeItem() {
        ChatMemoryItemDO expected = item(7L, "ACTIVE", "SINGLE", 4L,
                NOW_UTC.minusDays(2), 10L, 0);
        ChatMemoryItemDO unexpected = item(9L, "ACTIVE", "SINGLE", 2L,
                NOW_UTC.minusDays(2), 11L, 0);
        ChatMemoryItemDO candidate = item(8L, "QUARANTINED", "SINGLE", 3L,
                NOW_UTC.minusDays(1), 20L, 0);
        when(itemMapper.selectOwnedItem("user-a", 8L)).thenReturn(candidate);
        when(itemMapper.lockThemeRows("user-a", "PREFERENCE", "preference.editor"))
                .thenReturn(List.of(expected, candidate, unexpected));
        when(evolutionMapper.selectOwnedConflictTargets("user-a", 8L)).thenReturn(List.of(7L));

        assertCode(() -> service.confirmReplacement("user-a", 8L, 3L),
                "MEMORY_STATE_CONFLICT");

        verify(itemMapper, never()).markSuperseded(
                any(Long.class), any(), any(Long.class), any(Long.class), any());
    }

    @Test
    void replacementAndCoexistenceRejectStaleCandidateVersions() {
        ChatMemoryItemDO candidate = item(8L, "QUARANTINED", "SINGLE", 3L,
                NOW_UTC.minusDays(1), 20L, 0);
        when(itemMapper.selectOwnedItem("user-a", 8L)).thenReturn(candidate);
        when(itemMapper.lockThemeRows("user-a", "PREFERENCE", "preference.editor"))
                .thenReturn(List.of(candidate));
        assertCode(() -> service.confirmReplacement("user-a", 8L, 2L),
                "MEMORY_STATE_CONFLICT");

        ChatMemoryItemDO multi = item(9L, "QUARANTINED", "MULTI", 4L,
                NOW_UTC.minusDays(1), 30L, 0);
        when(itemMapper.lockOwnedItem("user-a", 9L)).thenReturn(multi);
        assertCode(() -> service.confirmCoexistence("user-a", 9L, 3L),
                "MEMORY_STATE_CONFLICT");
    }

    @Test
    void coexistenceActivatesOnlyMultiCandidate() {
        ChatMemoryItemDO multi = item(8L, "QUARANTINED", "MULTI", 3L,
                NOW_UTC.minusDays(1), 20L, 0);
        when(itemMapper.lockOwnedItem("user-a", 8L)).thenReturn(multi);
        when(itemMapper.activateQuarantined(8L, "user-a", 3L, 4L)).thenReturn(1);

        service.confirmCoexistence("user-a", 8L, 3L);

        verify(outboxEventService).enqueue(8L, "user-a", 4L, "UPSERT");

        ChatMemoryItemDO single = item(9L, "QUARANTINED", "SINGLE", 1L,
                NOW_UTC, 30L, 0);
        when(itemMapper.lockOwnedItem("user-a", 9L)).thenReturn(single);
        assertCode(() -> service.confirmCoexistence("user-a", 9L, 1L),
                "MEMORY_CARDINALITY_CONFLICT");
    }

    @Test
    void activeDeleteUsesVersionFenceRetainsSourcesAndQueuesDelete() {
        ChatMemoryItemDO active = item(7L, "ACTIVE", "MULTI", 3L,
                NOW_UTC.minusDays(1), 10L, 0);
        when(itemMapper.lockOwnedItem("user-a", 7L)).thenReturn(active);
        when(itemMapper.markDeleted(7L, "user-a", "ACTIVE", 3L, 4L, NOW_UTC)).thenReturn(1);

        service.delete("user-a", 7L, 3L);

        verify(itemMapper).markDeleted(7L, "user-a", "ACTIVE", 3L, 4L, NOW_UTC);
        verify(sourceMapper, never()).invalidateMemorySources(any(Long.class), any());
        verify(outboxEventService).enqueue(7L, "user-a", 4L, "DELETE");
    }

    @Test
    void quarantinedDeleteDoesNotQueueProjectionWork() {
        ChatMemoryItemDO quarantined = item(8L, "QUARANTINED", "MULTI", 6L,
                NOW_UTC.minusDays(1), 10L, 0);
        when(itemMapper.lockOwnedItem("user-a", 8L)).thenReturn(quarantined);
        when(itemMapper.markDeleted(8L, "user-a", "QUARANTINED", 6L, 7L, NOW_UTC))
                .thenReturn(1);

        service.delete("user-a", 8L, 6L);

        verify(outboxEventService, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void missingStaleOrChangedItemUsesStableNonDisclosingErrors() {
        when(itemMapper.lockOwnedItem("user-a", 10L)).thenReturn(null);
        assertCode(() -> service.delete("user-a", 10L, 1L), "MEMORY_NOT_FOUND");

        when(itemMapper.lockOwnedItem("user-a", 11L)).thenReturn(item(
                11L, "ACTIVE", "MULTI", 4L, NOW_UTC, 10L, 0));
        assertCode(() -> service.delete("user-a", 11L, 3L), "MEMORY_STATE_CONFLICT");

        when(itemMapper.lockOwnedItem("user-a", 12L)).thenReturn(item(
                12L, "SUPERSEDED", "MULTI", 1L, NOW_UTC, 10L, 0));
        assertCode(() -> service.delete("user-a", 12L, 1L), "MEMORY_STATE_CONFLICT");
    }

    @Test
    void everyManagementWriteIsTransactional() throws Exception {
        for (String method : List.of("confirmReplacement", "confirmCoexistence", "delete")) {
            Method declared = ChatMemoryManagementService.class.getMethod(
                    method, String.class, long.class, long.class);
            assertThat(declared.getAnnotation(Transactional.class)).isNotNull();
        }
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String code) {
        assertThatThrownBy(callable)
                .isInstanceOf(ChatMemoryManagementException.class)
                .extracting(exception -> ((ChatMemoryManagementException) exception).code())
                .isEqualTo(code);
    }

    private ChatMemoryItemDO item(
            long id, String status, String cardinality, long version,
            LocalDateTime originTime, long messagePk, int quoteStart) {
        ChatMemoryItemDO item = new ChatMemoryItemDO();
        item.setId(id);
        item.setUserId("user-a");
        item.setStatus(status);
        item.setCategory(id == 7L || "SINGLE".equals(cardinality) ? "PREFERENCE" : "FACT");
        item.setSubjectKey("SINGLE".equals(cardinality) ? "preference.editor" : "fact.current_city");
        item.setSubjectCardinality(cardinality);
        item.setContent(id == 7L ? "IntelliJ" : "杭州");
        item.setContentHash("hash-" + id);
        item.setVersion(version);
        item.setValidFrom(originTime);
        item.setOriginMessageCreatedAt(originTime);
        item.setOriginMessagePk(messagePk);
        item.setOriginQuoteStart(quoteStart);
        item.setCreatedAt(NOW_UTC.minusDays(4));
        item.setUpdatedAt(NOW_UTC.minusDays(1));
        return item;
    }

    private ChatMemorySourceDO source(long memoryId, String messageId) {
        ChatMemorySourceDO source = new ChatMemorySourceDO();
        source.setMemoryId(memoryId);
        source.setUserId("user-a");
        source.setSourceMessageId(messageId);
        return source;
    }

    private ChatMemoryEvolutionDO relation(long from, long to, String type) {
        ChatMemoryEvolutionDO relation = new ChatMemoryEvolutionDO();
        relation.setUserId("user-a");
        relation.setFromMemoryId(from);
        relation.setToMemoryId(to);
        relation.setRelationType(type);
        relation.setCreatedAt(NOW_UTC);
        return relation;
    }
}
