package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.application.contract.ChatMemoryOperations;
import io.github.imzmq.interview.chat.application.contract.result.ChatMemoryView;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ChatMemoryManagementService implements ChatMemoryOperations {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private final ChatMemoryItemMapper itemMapper;
    private final ChatMemorySourceMapper sourceMapper;
    private final ChatMemoryOutboxEventService outboxEventService;

    public ChatMemoryManagementService(
            ChatMemoryItemMapper itemMapper,
            ChatMemorySourceMapper sourceMapper,
            ChatMemoryOutboxEventService outboxEventService) {
        this.itemMapper = itemMapper;
        this.sourceMapper = sourceMapper;
        this.outboxEventService = outboxEventService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMemoryView> list(String userId, int limit, Long beforeId) {
        Objects.requireNonNull(userId, "userId");
        int pageSize = Math.max(MIN_PAGE_SIZE, Math.min(limit, MAX_PAGE_SIZE));
        List<ChatMemoryItemDO> items = itemMapper.selectManagementPage(userId, beforeId, pageSize);
        if (items.isEmpty()) {
            return List.of();
        }

        List<Long> memoryIds = items.stream().map(ChatMemoryItemDO::getId).toList();
        Map<Long, List<String>> sourceIdsByMemory = groupSourceMessageIds(
                sourceMapper.selectAccessibleSources(userId, memoryIds));
        return items.stream()
                .map(item -> toView(item, sourceIdsByMemory.getOrDefault(item.getId(), List.of())))
                .toList();
    }

    @Override
    @Transactional
    public void delete(String userId, long memoryId) {
        Objects.requireNonNull(userId, "userId");
        ChatMemoryItemDO item = itemMapper.lockOwnedItem(userId, memoryId);
        if (item == null || "DELETED".equals(item.getStatus())) {
            return;
        }

        long currentVersion = item.getVersion();
        long nextVersion = currentVersion + 1;
        int updated = itemMapper.markDeleted(memoryId, userId, currentVersion, nextVersion);
        if (updated != 1) {
            throw new IllegalStateException("CHAT_MEMORY_DELETE_CONFLICT");
        }
        sourceMapper.invalidateMemorySources(memoryId, userId);
        outboxEventService.enqueue(memoryId, userId, nextVersion, "DELETE");
    }

    private Map<Long, List<String>> groupSourceMessageIds(List<ChatMemorySourceDO> sources) {
        Map<Long, List<String>> result = new HashMap<>();
        for (ChatMemorySourceDO source : sources) {
            result.computeIfAbsent(source.getMemoryId(), ignored -> new ArrayList<>())
                    .add(source.getSourceMessageId());
        }
        result.replaceAll((ignored, messageIds) -> List.copyOf(messageIds));
        return result;
    }

    private ChatMemoryView toView(ChatMemoryItemDO item, List<String> sourceMessageIds) {
        return new ChatMemoryView(
                item.getId(),
                item.getCategory(),
                item.getContent(),
                item.getStatus(),
                item.getVersion(),
                toInstant(item.getCreatedAt()),
                toInstant(item.getUpdatedAt()),
                sourceMessageIds);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }
}
