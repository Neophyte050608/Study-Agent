package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class ChatMemoryRetryPolicy {

    private final ChatMemoryExtractionTaskMapper taskMapper;
    private final ChatMemoryProperties properties;

    public ChatMemoryRetryPolicy(
            ChatMemoryExtractionTaskMapper taskMapper,
            ChatMemoryProperties properties) {
        this.taskMapper = taskMapper;
        this.properties = properties;
    }

    public void recordFailure(ChatMemoryExtractionTaskDO task, String errorCode) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(errorCode, "errorCode");
        int attemptCount = Objects.requireNonNullElse(task.getAttemptCount(), 0) + 1;
        LocalDateTime now = LocalDateTime.now();
        int updated;
        if (attemptCount >= properties.getExtractionMaxAttempts()) {
            updated = taskMapper.markTaskDead(
                    task.getTaskId(), task.getLeaseToken(), attemptCount, errorCode, now);
        } else {
            LocalDateTime nextAttemptAt = now.plus(retryDelay(attemptCount));
            updated = taskMapper.rescheduleTask(
                    task.getTaskId(), task.getLeaseToken(), attemptCount,
                    nextAttemptAt, errorCode, now);
        }
        if (updated != 1) {
            throw new ChatMemoryCommitException("MEMORY_LEASE_LOST");
        }
    }

    public void resetDeadTask(String taskId) {
        if (taskMapper.resetDeadTask(taskId, LocalDateTime.now()) != 1) {
            throw new ChatMemoryCommitException("MEMORY_DEAD_TASK_NOT_FOUND");
        }
    }

    private Duration retryDelay(int attemptCount) {
        long multiplier = 1L << Math.min(Math.max(attemptCount - 1, 0), 10);
        return properties.getWorkerPoll().multipliedBy(multiplier);
    }
}
