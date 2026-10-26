package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
@ConditionalOnProperty(prefix = "app.chat.memory", name = "enabled", havingValue = "true")
public class ChatMemoryExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryExtractionWorker.class);

    private final ChatMemoryTaskClaimService claimService;
    private final ChatMemorySourceLoader sourceLoader;
    private final ChatMemoryExtractionService extractionService;
    private final ChatMemoryRetryPolicy retryPolicy;
    private final ChatMemoryProperties properties;
    private final ChatMemoryObservationPublisher observationPublisher;
    private final Executor executor;

    public ChatMemoryExtractionWorker(
            ChatMemoryTaskClaimService claimService,
            ChatMemorySourceLoader sourceLoader,
            ChatMemoryExtractionService extractionService,
            ChatMemoryRetryPolicy retryPolicy,
            ChatMemoryProperties properties,
            ChatMemoryObservationPublisher observationPublisher,
            @Qualifier("chatMemoryWorkerExecutor") Executor executor) {
        this.claimService = claimService;
        this.sourceLoader = sourceLoader;
        this.extractionService = extractionService;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        this.observationPublisher = observationPublisher;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${app.chat.memory.worker-poll:1s}")
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        Map<String, List<ChatMemoryExtractionTaskDO>> compatibleGroups = new LinkedHashMap<>();
        for (ChatMemoryExtractionTaskDO task : claimService.claimAvailableTasks()) {
            String key = task.getUserId() + "\u0000" + task.getSessionId();
            compatibleGroups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(task);
        }
        compatibleGroups.values().forEach(this::submit);
    }

    private void submit(List<ChatMemoryExtractionTaskDO> tasks) {
        try {
            executor.execute(() -> extract(tasks));
        } catch (RejectedExecutionException rejected) {
            tasks.forEach(task -> recordFailure(task, "MEMORY_EXECUTOR_REJECTED", rejected));
        }
    }

    private void extract(List<ChatMemoryExtractionTaskDO> tasks) {
        long startedAt = System.nanoTime();
        try {
            List<ChatMemorySourceMessage> sourceMessages = sourceLoader.loadBatch(tasks);
            extractionService.extractAndCommitBatch(tasks, sourceMessages);
            observationPublisher.extract("COMPLETED", Map.of(
                    "stage", "extract",
                    "taskCount", tasks.size(),
                    "resultCount", sourceMessages.size(),
                    "latencyMs", elapsedMillis(startedAt)
            ));
        } catch (ChatMemoryExtractionException exception) {
            tasks.forEach(task -> recordFailure(task, exception.code(), exception));
        } catch (ChatMemoryCommitException exception) {
            if (!"MEMORY_LEASE_LOST".equals(exception.code())) {
                tasks.forEach(task -> recordFailure(task, exception.code(), exception));
            }
        } catch (RuntimeException exception) {
            tasks.forEach(task -> recordFailure(task, "MEMORY_EXTRACTION_FAILED", exception));
        }
    }

    private void recordFailure(ChatMemoryExtractionTaskDO task, String code, RuntimeException cause) {
        try {
            retryPolicy.recordFailure(task, code);
        } catch (ChatMemoryCommitException leaseFailure) {
            log.debug("Chat memory task failure was not recorded: taskId={}, code={}, reason={}",
                    task.getTaskId(), code, leaseFailure.code());
            return;
        }
        boolean dead = Objects.requireNonNullElse(task.getAttemptCount(), 0) + 1
                >= properties.getExtractionMaxAttempts();
        observationPublisher.extract(dead ? "DEAD" : "RETRY", Map.of(
                "stage", "extract",
                "taskCount", 1,
                "pendingCount", dead ? 0 : 1,
                "deadCount", dead ? 1 : 0,
                "errorCode", code
        ));
        log.warn("Chat memory extraction scheduled for retry: taskId={}, code={}",
                task.getTaskId(), code);
    }

    private long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
