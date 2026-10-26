package io.github.imzmq.interview.chat.application.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryOutboxDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryOutboxMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
public class ChatMemoryOutboxEventService {

    private final ChatMemoryOutboxMapper outboxMapper;

    public ChatMemoryOutboxEventService(ChatMemoryOutboxMapper outboxMapper) {
        this.outboxMapper = outboxMapper;
    }

    @Transactional
    public void enqueue(
            Long memoryId, String userId, Long memoryVersion, String operation) {
        upsert(memoryId, userId, memoryVersion, operation, false);
    }

    @Transactional
    public void reactivateTerminal(
            Long memoryId, String userId, Long memoryVersion, String operation) {
        upsert(memoryId, userId, memoryVersion, operation, true);
    }

    private void upsert(
            Long memoryId,
            String userId,
            Long memoryVersion,
            String operation,
            boolean terminalOnly) {
        Objects.requireNonNull(memoryId, "memoryId");
        Objects.requireNonNull(memoryVersion, "memoryVersion");
        Objects.requireNonNull(operation, "operation");
        LocalDateTime now = LocalDateTime.now();
        ChatMemoryOutboxDO existing = outboxMapper.selectOne(
                new LambdaQueryWrapper<ChatMemoryOutboxDO>()
                        .eq(ChatMemoryOutboxDO::getMemoryId, memoryId)
                        .eq(ChatMemoryOutboxDO::getMemoryVersion, memoryVersion)
                        .eq(ChatMemoryOutboxDO::getTarget, "MILVUS")
                        .eq(ChatMemoryOutboxDO::getOperation, operation)
                        .last("LIMIT 1 FOR UPDATE"));
        if (existing == null) {
            insert(memoryId, userId, memoryVersion, operation, now);
            return;
        }
        if (terminalOnly
                && !"DONE".equals(existing.getStatus())
                && !"DEAD".equals(existing.getStatus())) {
            return;
        }
        reset(existing, userId, now);
    }

    private void insert(
            Long memoryId,
            String userId,
            Long memoryVersion,
            String operation,
            LocalDateTime now) {
        ChatMemoryOutboxDO event = new ChatMemoryOutboxDO();
        event.setEventId(UUID.randomUUID().toString());
        event.setMemoryId(memoryId);
        event.setUserId(userId);
        event.setMemoryVersion(memoryVersion);
        event.setTarget("MILVUS");
        event.setOperation(operation);
        event.setStatus("PENDING");
        event.setAttemptCount(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        outboxMapper.insert(event);
    }

    private void reset(ChatMemoryOutboxDO existing, String userId, LocalDateTime now) {
        existing.setUserId(userId);
        existing.setStatus("PENDING");
        existing.setAttemptCount(0);
        existing.setNextAttemptAt(now);
        existing.setLeaseToken(null);
        existing.setLeaseUntil(null);
        existing.setErrorCode(null);
        existing.setUpdatedAt(now);
        outboxMapper.updateById(existing);
    }
}
