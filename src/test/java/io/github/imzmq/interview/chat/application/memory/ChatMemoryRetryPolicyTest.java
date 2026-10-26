package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class ChatMemoryRetryPolicyTest {

    private final ChatMemoryExtractionTaskMapper mapper = mock(ChatMemoryExtractionTaskMapper.class);
    private final ChatMemoryProperties properties = new ChatMemoryProperties();
    private final ChatMemoryRetryPolicy policy = new ChatMemoryRetryPolicy(mapper, properties);

    @Test
    void retryBeforeMaximumUsesFencedReschedule() {
        properties.setExtractionMaxAttempts(5);
        properties.setWorkerPoll(Duration.ofSeconds(1));
        when(mapper.rescheduleTask(eq("task-1"), eq("lease-1"), eq(4), any(),
                eq("MEMORY_PARSE_FAILED"), any())).thenReturn(1);

        policy.recordFailure(task(3), "MEMORY_PARSE_FAILED");

        verify(mapper).rescheduleTask(eq("task-1"), eq("lease-1"), eq(4), any(),
                eq("MEMORY_PARSE_FAILED"), any());
    }

    @Test
    void fifthFailureMarksTaskDeadWithLeaseFence() {
        properties.setExtractionMaxAttempts(5);
        when(mapper.markTaskDead(eq("task-1"), eq("lease-1"), eq(5),
                eq("MEMORY_SOURCE_INVALID"), any())).thenReturn(1);

        policy.recordFailure(task(4), "MEMORY_SOURCE_INVALID");

        verify(mapper).markTaskDead(eq("task-1"), eq("lease-1"), eq(5),
                eq("MEMORY_SOURCE_INVALID"), any());
    }

    @Test
    void controlledResetOnlyTargetsPersistedDeadTask() {
        when(mapper.resetDeadTask(eq("task-1"), any())).thenReturn(1);

        policy.resetDeadTask("task-1");

        verify(mapper).resetDeadTask(eq("task-1"), any());
    }

    @Test
    void lostLeaseDuringRetryFailsInsteadOfSilentlyDroppingTask() {
        properties.setExtractionMaxAttempts(5);
        when(mapper.rescheduleTask(eq("task-1"), eq("lease-1"), eq(4), any(),
                eq("MEMORY_PARSE_FAILED"), any())).thenReturn(0);

        assertThatThrownBy(() -> policy.recordFailure(task(3), "MEMORY_PARSE_FAILED"))
                .isInstanceOf(ChatMemoryCommitException.class)
                .extracting("code")
                .isEqualTo("MEMORY_LEASE_LOST");
    }

    @Test
    void resetRejectsMissingOrNonDeadTask() {
        when(mapper.resetDeadTask(eq("task-1"), any())).thenReturn(0);

        assertThatThrownBy(() -> policy.resetDeadTask("task-1"))
                .isInstanceOf(ChatMemoryCommitException.class)
                .extracting("code")
                .isEqualTo("MEMORY_DEAD_TASK_NOT_FOUND");
    }

    private ChatMemoryExtractionTaskDO task(int attempts) {
        ChatMemoryExtractionTaskDO task = new ChatMemoryExtractionTaskDO();
        task.setTaskId("task-1");
        task.setLeaseToken("lease-1");
        task.setAttemptCount(attempts);
        return task;
    }
}
