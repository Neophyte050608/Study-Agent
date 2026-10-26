package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskDO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryExtractionWorkerTest {

    private final ChatMemoryTaskClaimService claimService = mock(ChatMemoryTaskClaimService.class);
    private final ChatMemorySourceLoader sourceLoader = mock(ChatMemorySourceLoader.class);
    private final ChatMemoryExtractionService extractionService = mock(ChatMemoryExtractionService.class);
    private final ChatMemoryRetryPolicy retryPolicy = mock(ChatMemoryRetryPolicy.class);
    private final ChatMemoryProperties properties = new ChatMemoryProperties();

    @Test
    void disabledWorkerDoesNotClaimTasks() {
        properties.setEnabled(false);
        ChatMemoryExtractionWorker worker = worker(Runnable::run);

        worker.poll();

        verify(claimService, never()).claimAvailableTasks();
    }

    @Test
    void claimedTaskLoadsBoundedContextAndExtractsOutsideClaimService() {
        properties.setEnabled(true);
        ChatMemoryExtractionTaskDO task = task();
        List<ChatMemorySourceMessage> context = List.of(source());
        when(claimService.claimAvailableTasks()).thenReturn(List.of(task));
        when(sourceLoader.loadBatch(List.of(task))).thenReturn(context);

        worker(Runnable::run).poll();

        verify(extractionService).extractAndCommitBatch(List.of(task), context);
        verify(retryPolicy, never()).recordFailure(task, "MEMORY_EXTRACTION_FAILED");
    }

    @Test
    void extractionFailureIsPersistedThroughFencedRetryPolicy() {
        properties.setEnabled(true);
        ChatMemoryExtractionTaskDO task = task();
        when(claimService.claimAvailableTasks()).thenReturn(List.of(task));
        when(sourceLoader.loadBatch(List.of(task))).thenThrow(new ChatMemoryExtractionException("MEMORY_SOURCE_INVALID"));

        worker(Runnable::run).poll();

        verify(retryPolicy).recordFailure(task, "MEMORY_SOURCE_INVALID");
    }


    @Test
    void compatibleTasksAreSubmittedAsOneBatch() {
        properties.setEnabled(true);
        ChatMemoryExtractionTaskDO first = task();
        ChatMemoryExtractionTaskDO second = task();
        second.setTaskId("task-2");
        second.setSourceMessageId("m-2");
        second.setSourceMessagePk(2L);
        when(claimService.claimAvailableTasks()).thenReturn(List.of(first, second));
        List<ChatMemorySourceMessage> context = List.of(source());
        when(sourceLoader.loadBatch(List.of(first, second))).thenReturn(context);

        worker(Runnable::run).poll();

        verify(sourceLoader).loadBatch(List.of(first, second));
        verify(extractionService).extractAndCommitBatch(List.of(first, second), context);
    }

    @Test
    void tasksFromDifferentSessionsAreNeverMixedInOneModelCall() {
        properties.setEnabled(true);
        ChatMemoryExtractionTaskDO first = task();
        ChatMemoryExtractionTaskDO second = task();
        second.setTaskId("task-2");
        second.setSessionId("session-2");
        when(claimService.claimAvailableTasks()).thenReturn(List.of(first, second));
        when(sourceLoader.loadBatch(List.of(first))).thenReturn(List.of(source()));
        when(sourceLoader.loadBatch(List.of(second))).thenReturn(List.of(source()));

        worker(Runnable::run).poll();

        verify(extractionService).extractAndCommitBatch(eq(List.of(first)), any());
        verify(extractionService).extractAndCommitBatch(eq(List.of(second)), any());
        verify(extractionService, never()).extractAndCommitBatch(eq(List.of(first, second)), any());
    }

    @Test
    void saturatedExecutorNeverRunsExtractionOnPollingThreadAndReschedulesTask() {
        properties.setEnabled(true);
        ChatMemoryExtractionTaskDO task = task();
        when(claimService.claimAvailableTasks()).thenReturn(List.of(task));
        Executor rejecting = command -> { throw new RejectedExecutionException("full"); };

        worker(rejecting).poll();

        verify(sourceLoader, never()).loadBatch(List.of(task));
        verify(extractionService, never()).extractAndCommitBatch(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(retryPolicy).recordFailure(task, "MEMORY_EXECUTOR_REJECTED");
    }

    private ChatMemoryExtractionWorker worker(Executor executor) {
        return new ChatMemoryExtractionWorker(
                claimService, sourceLoader, extractionService, retryPolicy, properties, mock(ChatMemoryObservationPublisher.class), executor);
    }

    private ChatMemoryExtractionTaskDO task() {
        ChatMemoryExtractionTaskDO task = new ChatMemoryExtractionTaskDO();
        task.setTaskId("task-1");
        task.setLeaseToken("lease-1");
        task.setUserId("user-1");
        task.setSessionId("session-1");
        task.setSourceMessageId("m-1");
        task.setSourceMessagePk(1L);
        task.setStatus("PROCESSING");
        return task;
    }

    private ChatMemorySourceMessage source() {
        return new ChatMemorySourceMessage(1L, "m-1", "user-1", "session-1",
                "user", false, "hello");
    }
}
