package io.github.imzmq.interview.chat.application;

import io.github.imzmq.interview.chat.application.memory.ChatMemoryOutboxEventService;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionDeletionServiceTest {

    private final ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
    private final ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
    private final ChatMemoryExtractionTaskMapper taskMapper = mock(ChatMemoryExtractionTaskMapper.class);
    private final ChatMemorySourceMapper sourceMapper = mock(ChatMemorySourceMapper.class);
    private final ChatMemoryItemMapper itemMapper = mock(ChatMemoryItemMapper.class);
    private final ChatMemoryOutboxEventService outboxEventService = mock(ChatMemoryOutboxEventService.class);
    private final ChatSessionDeletionService service = new ChatSessionDeletionService(
            sessionMapper,
            messageMapper,
            taskMapper,
            sourceMapper,
            itemMapper,
            outboxEventService);

    @Test
    void rejectsMissingOrForeignSessionWithoutDisclosingExistence() {
        when(sessionMapper.lockOwnedSession("user-a", "session-b")).thenReturn(null);

        assertThatThrownBy(() -> service.delete("user-a", "session-b"))
                .isInstanceOf(ChatSessionAccessException.class)
                .hasMessage("会话不存在");

        verify(sessionMapper, never()).markOwnedSessionDeleted("user-a", "session-b");
        verify(messageMapper, never()).markSessionMessagesDeleted("user-a", "session-b");
        verify(taskMapper, never()).cancelUnfinishedBySession("user-a", "session-b");
        verify(sourceMapper, never()).invalidateSessionSources("user-a", "session-b");
    }

    @Test
    void deletesLastSourceMemoryAndQueuesDeleteInTheSameOrchestration() {
        when(sessionMapper.lockOwnedSession("user-a", "session-a"))
                .thenReturn(session("session-a", "user-a"));
        when(sessionMapper.markOwnedSessionDeleted("user-a", "session-a")).thenReturn(1);
        when(sourceMapper.selectSessionMemoryIds("user-a", "session-a"))
                .thenReturn(List.of(7L));
        when(itemMapper.lockOwnedItem("user-a", 7L))
                .thenReturn(item(7L, "user-a", 3L));
        when(sourceMapper.countEffectiveSources("user-a", 7L)).thenReturn(0);
        when(itemMapper.markActiveDeleted(7L, "user-a", 3L, 4L)).thenReturn(1);

        service.delete("user-a", "session-a");

        verify(messageMapper).markSessionMessagesDeleted("user-a", "session-a");
        verify(taskMapper).cancelUnfinishedBySession("user-a", "session-a");
        verify(sourceMapper).invalidateSessionSources("user-a", "session-a");
        verify(outboxEventService).enqueue(7L, "user-a", 4L, "DELETE");

        InOrder order = inOrder(sessionMapper, messageMapper, taskMapper, sourceMapper, itemMapper);
        order.verify(sessionMapper).lockOwnedSession("user-a", "session-a");
        order.verify(sourceMapper).selectSessionMemoryIds("user-a", "session-a");
        order.verify(sessionMapper).markOwnedSessionDeleted("user-a", "session-a");
        order.verify(messageMapper).markSessionMessagesDeleted("user-a", "session-a");
        order.verify(taskMapper).cancelUnfinishedBySession("user-a", "session-a");
        order.verify(sourceMapper).invalidateSessionSources("user-a", "session-a");
        order.verify(itemMapper).lockOwnedItem("user-a", 7L);
        order.verify(sourceMapper).countEffectiveSources("user-a", 7L);
        order.verify(itemMapper).markActiveDeleted(7L, "user-a", 3L, 4L);
    }

    @Test
    void preservesMemoryWithOtherEffectiveSourcesAndProcessesIdsInAscendingOrder() {
        when(sessionMapper.lockOwnedSession("user-a", "session-a"))
                .thenReturn(session("session-a", "user-a"));
        when(sessionMapper.markOwnedSessionDeleted("user-a", "session-a")).thenReturn(1);
        when(sourceMapper.selectSessionMemoryIds("user-a", "session-a"))
                .thenReturn(List.of(9L, 7L, 9L));
        when(itemMapper.lockOwnedItem("user-a", 7L))
                .thenReturn(item(7L, "user-a", 2L));
        when(itemMapper.lockOwnedItem("user-a", 9L))
                .thenReturn(item(9L, "user-a", 5L));
        when(sourceMapper.countEffectiveSources("user-a", 7L)).thenReturn(1);
        when(sourceMapper.countEffectiveSources("user-a", 9L)).thenReturn(2);
        when(itemMapper.bumpVersion(7L, "user-a", 2L, 3L)).thenReturn(1);
        when(itemMapper.bumpVersion(9L, "user-a", 5L, 6L)).thenReturn(1);

        service.delete("user-a", "session-a");

        InOrder itemOrder = inOrder(itemMapper);
        itemOrder.verify(itemMapper).lockOwnedItem("user-a", 7L);
        itemOrder.verify(itemMapper).bumpVersion(7L, "user-a", 2L, 3L);
        itemOrder.verify(itemMapper).lockOwnedItem("user-a", 9L);
        itemOrder.verify(itemMapper).bumpVersion(9L, "user-a", 5L, 6L);
        verify(outboxEventService).enqueue(7L, "user-a", 3L, "UPSERT");
        verify(outboxEventService).enqueue(9L, "user-a", 6L, "UPSERT");
        verify(itemMapper, never()).markActiveDeleted(7L, "user-a", 2L, 3L);
        verify(itemMapper, never()).markActiveDeleted(9L, "user-a", 5L, 6L);
    }

    @Test
    void quarantinedLastSourceIsDeletedWithoutProjectionEvent() {
        arrangeAffectedMemory(item(7L, "user-a", "QUARANTINED", 3L), 0);
        when(itemMapper.markQuarantinedDeleted(7L, "user-a", 3L, 4L)).thenReturn(1);

        service.delete("user-a", "session-a");

        verify(itemMapper).markQuarantinedDeleted(7L, "user-a", 3L, 4L);
        verify(outboxEventService, never()).enqueue(7L, "user-a", 4L, "DELETE");
        verify(outboxEventService, never()).enqueue(7L, "user-a", 4L, "UPSERT");
    }

    @Test
    void quarantinedMemoryWithAnotherSourceOnlyBumpsItsVersion() {
        arrangeAffectedMemory(item(7L, "user-a", "QUARANTINED", 3L), 1);
        when(itemMapper.bumpQuarantinedVersion(7L, "user-a", 3L, 4L)).thenReturn(1);

        service.delete("user-a", "session-a");

        verify(itemMapper).bumpQuarantinedVersion(7L, "user-a", 3L, 4L);
        verify(outboxEventService, never()).enqueue(7L, "user-a", 4L, "DELETE");
        verify(outboxEventService, never()).enqueue(7L, "user-a", 4L, "UPSERT");
    }

    @Test
    void supersededOrDeletedMemoryStateIsNotChangedWhenSourcesDisappear() {
        for (String status : List.of("SUPERSEDED", "DELETED")) {
            ChatMemoryItemDO item = item(7L, "user-a", status, 3L);
            when(sessionMapper.lockOwnedSession("user-a", "session-a"))
                    .thenReturn(session("session-a", "user-a"));
            when(sessionMapper.markOwnedSessionDeleted("user-a", "session-a")).thenReturn(1);
            when(sourceMapper.selectSessionMemoryIds("user-a", "session-a"))
                    .thenReturn(List.of(7L));
            when(itemMapper.lockOwnedItem("user-a", 7L)).thenReturn(item);

            service.delete("user-a", "session-a");
        }

        verify(sourceMapper, never()).countEffectiveSources("user-a", 7L);
        verify(itemMapper, never()).markActiveDeleted(7L, "user-a", 3L, 4L);
        verify(itemMapper, never()).markQuarantinedDeleted(7L, "user-a", 3L, 4L);
        verify(itemMapper, never()).bumpVersion(7L, "user-a", 3L, 4L);
        verify(itemMapper, never()).bumpQuarantinedVersion(7L, "user-a", 3L, 4L);
        verify(outboxEventService, never()).enqueue(7L, "user-a", 4L, "DELETE");
        verify(outboxEventService, never()).enqueue(7L, "user-a", 4L, "UPSERT");
    }

    @Test
    void failsAtomicallyWhenSessionCompareAndSetLosesOwnershipRace() {
        when(sessionMapper.lockOwnedSession("user-a", "session-a"))
                .thenReturn(session("session-a", "user-a"));
        when(sourceMapper.selectSessionMemoryIds("user-a", "session-a"))
                .thenReturn(List.of());
        when(sessionMapper.markOwnedSessionDeleted("user-a", "session-a")).thenReturn(0);

        assertThatThrownBy(() -> service.delete("user-a", "session-a"))
                .isInstanceOf(ChatSessionAccessException.class)
                .hasMessage("会话不存在");

        verify(messageMapper, never()).markSessionMessagesDeleted("user-a", "session-a");
        verify(taskMapper, never()).cancelUnfinishedBySession("user-a", "session-a");
    }

    private ChatSessionDO session(String sessionId, String userId) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setDeleted(false);
        return session;
    }

    private void arrangeAffectedMemory(ChatMemoryItemDO item, int sourceCount) {
        when(sessionMapper.lockOwnedSession("user-a", "session-a"))
                .thenReturn(session("session-a", "user-a"));
        when(sessionMapper.markOwnedSessionDeleted("user-a", "session-a")).thenReturn(1);
        when(sourceMapper.selectSessionMemoryIds("user-a", "session-a"))
                .thenReturn(List.of(item.getId()));
        when(itemMapper.lockOwnedItem("user-a", item.getId())).thenReturn(item);
        when(sourceMapper.countEffectiveSources("user-a", item.getId())).thenReturn(sourceCount);
    }

    private ChatMemoryItemDO item(long id, String userId, long version) {
        return item(id, userId, "ACTIVE", version);
    }

    private ChatMemoryItemDO item(long id, String userId, String status, long version) {
        ChatMemoryItemDO item = new ChatMemoryItemDO();
        item.setId(id);
        item.setUserId(userId);
        item.setStatus(status);
        item.setVersion(version);
        item.setContent("memory");
        return item;
    }
}
