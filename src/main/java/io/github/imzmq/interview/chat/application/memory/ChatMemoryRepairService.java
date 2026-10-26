package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.infrastructure.milvus.ChatMemoryProjectionCursor;
import io.github.imzmq.interview.chat.infrastructure.milvus.ChatMemoryProjectionPort;
import io.github.imzmq.interview.chat.infrastructure.milvus.ChatMemoryProjectionRow;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "app.chat.memory.enabled", havingValue = "true")
public class ChatMemoryRepairService {

    private final ChatMemoryItemMapper itemMapper;
    private final ChatMemorySourceMapper sourceMapper;
    private final ChatMemoryProjectionPort projectionPort;
    private final ChatMemoryOutboxEventService outboxEventService;
    private final ChatMemoryProperties properties;
    private final ChatMemoryObservationPublisher observationPublisher;

    public ChatMemoryRepairService(
            ChatMemoryItemMapper itemMapper,
            ChatMemorySourceMapper sourceMapper,
            ChatMemoryProjectionPort projectionPort,
            ChatMemoryOutboxEventService outboxEventService,
            ChatMemoryProperties properties,
            ChatMemoryObservationPublisher observationPublisher) {
        this.itemMapper = itemMapper;
        this.sourceMapper = sourceMapper;
        this.projectionPort = projectionPort;
        this.outboxEventService = outboxEventService;
        this.properties = properties;
        this.observationPublisher = observationPublisher;
    }

    public RepairSummary repairAll() {
        long startedAt = System.nanoTime();
        Set<RepairKey> queued = new HashSet<>();
        int truthRows = scanTruth(queued);
        int projectionRows = scanProjection(queued);
        RepairSummary summary = new RepairSummary(truthRows, projectionRows, queued.size());
        observationPublisher.repair("COMPLETED", Map.of(
                "stage", "repair",
                "resultCount", truthRows + projectionRows,
                "queuedCount", queued.size(),
                "latencyMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        ));
        return summary;
    }

    private int scanTruth(Set<RepairKey> queued) {
        int scanned = 0;
        long afterId = 0;
        int pageSize = properties.getRepairPageSize();
        Duration timeout = properties.getRpcTimeout();
        while (true) {
            List<ChatMemoryItemDO> items = itemMapper.selectRepairPage(afterId, pageSize);
            if (items.isEmpty()) {
                return scanned;
            }
            scanned += items.size();
            List<String> currentIds = items.stream()
                    .map(this::projectionId)
                    .toList();
            Set<Long> effectiveSourceIds = effectiveSourceIds(items.stream()
                    .map(ChatMemoryItemDO::getId)
                    .toList());
            Map<String, ChatMemoryProjectionRow> rowsById = new HashMap<>();
            for (ChatMemoryProjectionRow row :
                    projectionPort.findByProjectionIds(currentIds, timeout)) {
                rowsById.put(row.projectionId(), row);
            }
            for (ChatMemoryItemDO item : items) {
                ChatMemoryProjectionRow row = rowsById.get(projectionId(item));
                if (isProjectable(item, effectiveSourceIds)) {
                    if (row == null || !matches(item, row)) {
                        queue(item.getId(), item.getUserId(), item.getVersion(), "UPSERT", queued);
                    }
                } else if (row != null) {
                    queue(row.memoryId(), row.userId(), row.memoryVersion(), "DELETE", queued);
                }
            }
            afterId = items.getLast().getId();
            if (items.size() < pageSize) {
                return scanned;
            }
        }
    }

    private int scanProjection(Set<RepairKey> queued) {
        int scanned = 0;
        try (ChatMemoryProjectionCursor cursor = projectionPort.openCursor(
                properties.getRepairPageSize(), properties.getRpcTimeout())) {
            while (true) {
                List<ChatMemoryProjectionRow> rows = cursor.nextPage();
                if (rows.isEmpty()) {
                    return scanned;
                }
                scanned += rows.size();
                List<Long> memoryIds = rows.stream()
                        .map(ChatMemoryProjectionRow::memoryId)
                        .distinct()
                        .toList();
                Map<Long, ChatMemoryItemDO> truthById = new HashMap<>();
                itemMapper.selectBatchIds(memoryIds)
                        .forEach(item -> truthById.put(item.getId(), item));
                Set<Long> effectiveSourceIds = effectiveSourceIds(memoryIds);
                for (ChatMemoryProjectionRow row : rows) {
                    ChatMemoryItemDO item = truthById.get(row.memoryId());
                    if (item == null || !isProjectable(item, effectiveSourceIds)) {
                        queue(row.memoryId(), row.userId(), row.memoryVersion(), "DELETE", queued);
                    } else if (projectionId(item).equals(row.projectionId())) {
                        if (!matches(item, row)) {
                            queue(item.getId(), item.getUserId(), item.getVersion(), "UPSERT", queued);
                        }
                    } else {
                        queue(row.memoryId(), row.userId(), row.memoryVersion(), "DELETE", queued);
                    }
                }
            }
        }
    }

    private boolean isProjectable(ChatMemoryItemDO item, Set<Long> effectiveSourceIds) {
        return "ACTIVE".equals(item.getStatus()) && effectiveSourceIds.contains(item.getId());
    }

    private Set<Long> effectiveSourceIds(List<Long> memoryIds) {
        if (memoryIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(sourceMapper.selectEffectiveMemoryIds(memoryIds));
    }

    private boolean matches(ChatMemoryItemDO item, ChatMemoryProjectionRow row) {
        return projectionId(item).equals(row.projectionId())
                && item.getId() == row.memoryId()
                && item.getVersion() == row.memoryVersion()
                && item.getUserId().equals(row.userId())
                && item.getContentHash().equals(row.contentHash())
                && item.getCategory().equals(row.category());
    }

    private String projectionId(ChatMemoryItemDO item) {
        return item.getId() + ":" + item.getVersion();
    }

    private void queue(
            long memoryId,
            String userId,
            long memoryVersion,
            String operation,
            Set<RepairKey> queued) {
        RepairKey key = new RepairKey(memoryId, memoryVersion, operation);
        if (queued.add(key)) {
            outboxEventService.reactivateTerminal(
                    memoryId, userId, memoryVersion, operation);
        }
    }

    public record RepairSummary(int truthRows, int projectionRows, int queuedEvents) {
    }

    private record RepairKey(long memoryId, long memoryVersion, String operation) {
    }
}
