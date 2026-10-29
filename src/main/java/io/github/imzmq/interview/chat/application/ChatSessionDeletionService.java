package io.github.imzmq.interview.chat.application;

import io.github.imzmq.interview.chat.application.memory.ChatMemoryOutboxEventService;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Owns the atomic propagation from a logical Chat session deletion into structured memory state.
 */
@Service
public class ChatSessionDeletionService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatMemoryExtractionTaskMapper taskMapper;
    private final ChatMemorySourceMapper sourceMapper;
    private final ChatMemoryItemMapper itemMapper;
    private final ChatMemoryOutboxEventService outboxEventService;

    public ChatSessionDeletionService(
            ChatSessionMapper sessionMapper,
            ChatMessageMapper messageMapper,
            ChatMemoryExtractionTaskMapper taskMapper,
            ChatMemorySourceMapper sourceMapper,
            ChatMemoryItemMapper itemMapper,
            ChatMemoryOutboxEventService outboxEventService) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.taskMapper = taskMapper;
        this.sourceMapper = sourceMapper;
        this.itemMapper = itemMapper;
        this.outboxEventService = outboxEventService;
    }

    @Transactional
    public void delete(String userId, String sessionId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        ChatSessionDO session = sessionMapper.lockOwnedSession(userId, sessionId);
        if (session == null) {
            throw new ChatSessionAccessException();
        }

        List<Long> affectedMemoryIds = sourceMapper.selectSessionMemoryIds(userId, sessionId).stream()
                .distinct()
                .sorted()
                .toList();
        if (sessionMapper.markOwnedSessionDeleted(userId, sessionId) != 1) {
            throw new ChatSessionAccessException();
        }
        messageMapper.markSessionMessagesDeleted(userId, sessionId);
        taskMapper.cancelUnfinishedBySession(userId, sessionId);
        sourceMapper.invalidateSessionSources(userId, sessionId);
        affectedMemoryIds.forEach(memoryId -> propagateMemoryChange(userId, memoryId));
    }

    private void propagateMemoryChange(String userId, long memoryId) {
        ChatMemoryItemDO item = itemMapper.lockOwnedItem(userId, memoryId);
        if (item == null
                || (!("ACTIVE".equals(item.getStatus()))
                && !("QUARANTINED".equals(item.getStatus())))) {
            return;
        }
        long currentVersion = item.getVersion();
        long nextVersion = currentVersion + 1;
        boolean active = "ACTIVE".equals(item.getStatus());
        if (sourceMapper.countEffectiveSources(userId, memoryId) == 0) {
            int updated = active
                    ? itemMapper.markActiveDeleted(memoryId, userId, currentVersion, nextVersion)
                    : itemMapper.markQuarantinedDeleted(memoryId, userId, currentVersion, nextVersion);
            requireSingleUpdate(updated);
            if (active) {
                outboxEventService.enqueue(memoryId, userId, nextVersion, "DELETE");
            }
            return;
        }
        int updated = active
                ? itemMapper.bumpVersion(memoryId, userId, currentVersion, nextVersion)
                : itemMapper.bumpQuarantinedVersion(memoryId, userId, currentVersion, nextVersion);
        requireSingleUpdate(updated);
        if (active) {
            outboxEventService.enqueue(memoryId, userId, nextVersion, "UPSERT");
        }
    }

    private void requireSingleUpdate(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("CHAT_MEMORY_VERSION_CONFLICT");
        }
    }
}
