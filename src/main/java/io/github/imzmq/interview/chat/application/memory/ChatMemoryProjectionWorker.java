package io.github.imzmq.interview.chat.application.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.chat.infrastructure.milvus.ChatMemoryProjection;
import io.github.imzmq.interview.chat.infrastructure.milvus.ChatMemoryProjectionPort;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryOutboxDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryOutboxMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.chat.memory.enabled", havingValue = "true")
public class ChatMemoryProjectionWorker {

    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);

    private final ChatMemoryOutboxClaimService claimService;
    private final ChatMemoryItemMapper itemMapper;
    private final ChatMemorySourceMapper sourceMapper;
    private final ChatMemoryOutboxMapper outboxMapper;
    private final ChatMemoryProjectionPort projectionPort;
    private final ChatMemoryProperties properties;
    private final ChatMemoryObservationPublisher observationPublisher;

    public ChatMemoryProjectionWorker(
            ChatMemoryOutboxClaimService claimService,
            ChatMemoryItemMapper itemMapper,
            ChatMemorySourceMapper sourceMapper,
            ChatMemoryOutboxMapper outboxMapper,
            ChatMemoryProjectionPort projectionPort,
            ChatMemoryProperties properties,
            ChatMemoryObservationPublisher observationPublisher) {
        this.claimService = claimService;
        this.itemMapper = itemMapper;
        this.sourceMapper = sourceMapper;
        this.outboxMapper = outboxMapper;
        this.projectionPort = projectionPort;
        this.properties = properties;
        this.observationPublisher = observationPublisher;
    }

    @Scheduled(fixedDelayString = "${app.chat.memory.worker-poll:1s}")
    public void poll() {
        for (ChatMemoryOutboxDO event : claimService.claimAvailableEvents()) {
            try {
                process(event);
            } catch (ChatMemoryCommitException exception) {
                log.debug("Chat memory projection lease was lost: eventId={}, code={}",
                        event.getEventId(), exception.code());
            }
        }
    }

    public void process(ChatMemoryOutboxDO event) {
        Objects.requireNonNull(event, "event");
        long startedAt = System.nanoTime();
        try {
            projectCurrentTruth(event);
            acknowledge(event);
            observationPublisher.project("COMPLETED", Map.of(
                    "stage", "project",
                    "resultCount", 1,
                    "latencyMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            ));
        } catch (ChatMemoryCommitException exception) {
            throw exception;
        } catch (ChatMemoryProjectionException exception) {
            recordFailure(event, exception.code());
        } catch (RuntimeException exception) {
            log.warn("Chat memory projection failed: eventId={}, code={}",
                    event.getEventId(), "MEMORY_PROJECTION_FAILED");
            recordFailure(event, "MEMORY_PROJECTION_FAILED");
        }
    }

    private void projectCurrentTruth(ChatMemoryOutboxDO event) {
        ChatMemoryItemDO item = itemMapper.selectById(event.getMemoryId());
        Duration timeout = properties.getRpcTimeout();
        String eventUserId = requiredUserId(event.getUserId());
        long eventVersion = requiredVersion(event.getMemoryVersion());
        if (item == null) {
            projectionPort.deleteMemory(eventUserId, event.getMemoryId(), timeout);
            return;
        }

        long currentVersion = requiredVersion(item.getVersion());
        if ("DELETE".equals(event.getOperation())) {
            if (eventUserId.equals(item.getUserId()) && eventVersion == currentVersion) {
                projectionPort.deleteMemory(eventUserId, item.getId(), timeout);
            } else {
                projectionPort.deleteVersion(
                        eventUserId, event.getMemoryId(), eventVersion, timeout);
            }
            return;
        }
        if (item.getUserId() == null || !item.getUserId().equals(eventUserId)) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_OWNER_MISMATCH");
        }

        if (eventVersion < currentVersion) {
            projectionPort.deleteVersion(item.getUserId(), item.getId(), eventVersion, timeout);
            return;
        }
        if (eventVersion > currentVersion) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_VERSION_AHEAD");
        }
        if (!"ACTIVE".equals(item.getStatus()) || !hasEffectiveSource(item)) {
            projectionPort.deleteMemory(item.getUserId(), item.getId(), timeout);
            return;
        }
        if (!"UPSERT".equals(event.getOperation())) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_OPERATION_INVALID");
        }

        projectionPort.upsert(new ChatMemoryProjection(
                item.getId() + ":" + item.getVersion(),
                item.getId(),
                item.getUserId(),
                item.getVersion(),
                item.getContentHash(),
                item.getCategory(),
                item.getContent()), timeout);
        projectionPort.deleteOtherVersions(
                item.getUserId(), item.getId(), item.getVersion(), timeout);
    }

    private boolean hasEffectiveSource(ChatMemoryItemDO item) {
        return sourceMapper.selectCount(new LambdaQueryWrapper<ChatMemorySourceDO>()
                .eq(ChatMemorySourceDO::getMemoryId, item.getId())
                .eq(ChatMemorySourceDO::getUserId, item.getUserId())
                .isNull(ChatMemorySourceDO::getInvalidatedAt)) > 0;
    }

    private void acknowledge(ChatMemoryOutboxDO event) {
        if (outboxMapper.completeEvent(
                event.getEventId(), event.getLeaseToken(), LocalDateTime.now()) != 1) {
            throw new ChatMemoryCommitException("MEMORY_LEASE_LOST");
        }
    }

    private void recordFailure(ChatMemoryOutboxDO event, String errorCode) {
        int attempts = Objects.requireNonNullElse(event.getAttemptCount(), 0) + 1;
        LocalDateTime now = LocalDateTime.now();
        int updated;
        boolean dead = attempts >= properties.getProjectionMaxAttempts();
        if (dead) {
            updated = outboxMapper.markEventDead(
                    event.getEventId(), event.getLeaseToken(), attempts, errorCode, now);
        } else {
            updated = outboxMapper.rescheduleEvent(
                    event.getEventId(), event.getLeaseToken(), attempts,
                    now.plus(retryDelay(attempts)), errorCode, now);
        }
        if (updated != 1) {
            throw new ChatMemoryCommitException("MEMORY_LEASE_LOST");
        }
        observationPublisher.project(dead ? "DEAD" : "RETRY", Map.of(
                "stage", "project",
                "pendingCount", dead ? 0 : 1,
                "deadCount", dead ? 1 : 0,
                "errorCode", errorCode
        ));
    }

    private Duration retryDelay(int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 10);
        Duration delay = properties.getWorkerPoll().multipliedBy(multiplier);
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    private String requiredUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_USER_INVALID");
        }
        return userId;
    }

    private long requiredVersion(Long version) {
        if (version == null || version < 1) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_VERSION_INVALID");
        }
        return version;
    }
}
