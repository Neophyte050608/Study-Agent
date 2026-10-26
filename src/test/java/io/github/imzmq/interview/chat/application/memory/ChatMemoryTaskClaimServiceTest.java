package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryTaskClaimServiceTest {

    private final ChatMemoryExtractionTaskMapper mapper = mock(ChatMemoryExtractionTaskMapper.class);
    private final ChatMemoryProperties properties = new ChatMemoryProperties();
    private final ChatMemoryTaskClaimService service = new ChatMemoryTaskClaimService(mapper, properties);

    @Test
    void claimBoundaryIsTransactional() throws Exception {
        Method method = ChatMemoryTaskClaimService.class.getMethod("claimAvailableTasks");
        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void claimsAtMostEightAndPublishesLeaseOnlyAfterSuccessfulUpdate() {
        properties.setExtractionBatchSize(20);
        properties.setWorkerLease(java.time.Duration.ofSeconds(60));
        ChatMemoryExtractionTaskDO first = task("task-1");
        ChatMemoryExtractionTaskDO raced = task("task-2");
        when(mapper.lockClaimableTasks(any(), eq(8))).thenReturn(List.of(first, raced));
        when(mapper.leaseTask(eq("task-1"), any(), any(), any())).thenReturn(1);
        when(mapper.leaseTask(eq("task-2"), any(), any(), any())).thenReturn(0);

        List<ChatMemoryExtractionTaskDO> claimed = service.claimAvailableTasks();

        assertThat(claimed).containsExactly(first);
        assertThat(first.getStatus()).isEqualTo("PROCESSING");
        assertThat(first.getLeaseToken()).isNotBlank();
        assertThat(first.getLeaseUntil()).isAfter(LocalDateTime.now());
        verify(mapper).lockClaimableTasks(any(), eq(8));
    }

    private ChatMemoryExtractionTaskDO task(String taskId) {
        ChatMemoryExtractionTaskDO task = new ChatMemoryExtractionTaskDO();
        task.setTaskId(taskId);
        task.setStatus("PENDING");
        task.setAttemptCount(0);
        return task;
    }
}
