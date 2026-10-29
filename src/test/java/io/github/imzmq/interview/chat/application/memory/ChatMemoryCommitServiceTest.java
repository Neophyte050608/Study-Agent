package io.github.imzmq.interview.chat.application.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCandidate;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCardinality;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCategory;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryNormalizer;
import io.github.imzmq.interview.chat.domain.memory.ChatMemorySourceQuote;
import io.github.imzmq.interview.chat.domain.memory.ChatMemorySubjectKey;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryCommitServiceTest {

    private static final LocalDateTime MESSAGE_TIME = LocalDateTime.of(2026, 10, 29, 9, 0);

    private final ChatMemoryExtractionTaskMapper taskMapper = mock(ChatMemoryExtractionTaskMapper.class);
    private final ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
    private final ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
    private final ChatMemoryLifecycleWriter lifecycleWriter = mock(ChatMemoryLifecycleWriter.class);
    private final ChatMemoryCommitTransactionService service = new ChatMemoryCommitTransactionService(
            taskMapper, sessionMapper, messageMapper, lifecycleWriter);

    @Test
    void commitBoundariesRequireFreshTransactions() throws Exception {
        Method single = ChatMemoryCommitTransactionService.class.getMethod(
                "commitOnce", String.class, String.class, List.class);
        Method batch = ChatMemoryCommitTransactionService.class.getMethod(
                "commitBatchOnce", List.class);

        assertThat(single.getAnnotation(Transactional.class)).isNotNull();
        assertThat(single.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(batch.getAnnotation(Transactional.class)).isNotNull();
        assertThat(batch.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void lostLeaseRejectsCommitBeforeLifecycleWrites() {
        when(taskMapper.selectTaskIdentity("task-1")).thenReturn(task());
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session());
        when(taskMapper.lockOwnedTask(eq("task-1"), eq("stale"), any())).thenReturn(null);

        assertThatThrownBy(() -> service.commitOnce("task-1", "stale", List.of(candidate())))
                .isInstanceOf(ChatMemoryCommitException.class)
                .extracting("code")
                .isEqualTo("MEMORY_LEASE_LOST");

        verify(lifecycleWriter, never()).writeBatch(any(), any());
        verify(taskMapper, never()).markTaskDone(any(), any(), any());
    }

    @Test
    void sourceValidationRejectsUntrustedQuoteBeforeLifecycleWrites() {
        when(taskMapper.selectTaskIdentity("task-1")).thenReturn(task());
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session());
        when(taskMapper.lockOwnedTask(eq("task-1"), eq("lease-1"), any())).thenReturn(task());
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(message("m-1", 1L, MESSAGE_TIME));
        ChatMemoryCandidate invalid = new ChatMemoryCandidate(
                ChatMemoryCategory.FACT,
                ChatMemorySubjectKey.parse(ChatMemoryCategory.FACT, "fact.test"),
                "x",
                new ChatMemorySourceQuote("m-1", 0, 4, "nope"));

        assertThatThrownBy(() -> service.commitOnce("task-1", "lease-1", List.of(invalid)))
                .isInstanceOf(ChatMemoryCommitException.class)
                .extracting("code")
                .isEqualTo("MEMORY_SOURCE_INVALID");

        verify(lifecycleWriter, never()).writeBatch(any(), any());
    }

    @Test
    void constructsLifecycleRequestFromLockedAuthoritativeMessageAndCompletesAfterWrite() {
        when(taskMapper.selectTaskIdentity("task-1")).thenReturn(task());
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session());
        when(taskMapper.lockOwnedTask(eq("task-1"), eq("lease-1"), any())).thenReturn(task());
        when(messageMapper.selectOne(any(Wrapper.class)))
                .thenReturn(message("m-1", 1L, MESSAGE_TIME));
        when(taskMapper.markTaskDone(eq("task-1"), eq("lease-1"), any())).thenReturn(1);

        service.commitOnce("task-1", "lease-1", List.of(candidate()));

        ArgumentCaptor<List<ChatMemoryLifecycleRequest>> requests = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<LocalDateTime> writeTime = ArgumentCaptor.forClass(LocalDateTime.class);
        var order = inOrder(lifecycleWriter, taskMapper);
        order.verify(lifecycleWriter).writeBatch(requests.capture(), writeTime.capture());
        order.verify(taskMapper).markTaskDone(eq("task-1"), eq("lease-1"), eq(writeTime.getValue()));

        assertThat(requests.getValue()).singleElement().satisfies(request -> {
            assertThat(request.userId()).isEqualTo("user-1");
            assertThat(request.sessionId()).isEqualTo("session-1");
            assertThat(request.contentHash()).isEqualTo(
                    ChatMemoryNormalizer.contentHash(ChatMemoryCategory.FACT, "x"));
            assertThat(request.cardinality()).isEqualTo(ChatMemoryCardinality.MULTI);
            assertThat(request.originMessageCreatedAt()).isEqualTo(MESSAGE_TIME);
            assertThat(request.originMessagePk()).isEqualTo(1L);
            assertThat(request.originQuoteStart()).isZero();
            assertThat(request.ambiguousBatch()).isFalse();
        });
    }

    @Test
    void batchValidatesEveryTaskThenDelegatesOneAtomicLifecycleWrite() {
        ChatMemoryExtractionTaskDO task1 = task("task-1", "lease-1", "m-1", 1L);
        ChatMemoryExtractionTaskDO task2 = task("task-2", "lease-2", "m-2", 2L);
        when(taskMapper.selectTaskIdentity("task-1")).thenReturn(task1);
        when(taskMapper.selectTaskIdentity("task-2")).thenReturn(task2);
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session());
        when(taskMapper.lockOwnedTask(eq("task-1"), eq("lease-1"), any())).thenReturn(task1);
        when(taskMapper.lockOwnedTask(eq("task-2"), eq("lease-2"), any())).thenReturn(task2);
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(
                message("m-1", 1L, MESSAGE_TIME),
                message("m-2", 2L, MESSAGE_TIME.plusSeconds(1)));
        when(taskMapper.markTaskDone(any(), any(), any())).thenReturn(1);
        ChatMemoryCandidate first = candidate("m-1", "z");
        ChatMemoryCandidate second = candidate("m-2", "a");

        service.commitBatchOnce(List.of(request(task2, second), request(task1, first)));

        ArgumentCaptor<List<ChatMemoryLifecycleRequest>> requests = ArgumentCaptor.forClass(List.class);
        verify(lifecycleWriter).writeBatch(requests.capture(), any());
        assertThat(requests.getValue())
                .extracting(request -> request.candidate().source().sourceMessageId())
                .containsExactly("m-1", "m-2");
        assertThat(requests.getValue())
                .extracting(ChatMemoryLifecycleRequest::originMessageCreatedAt)
                .containsExactly(MESSAGE_TIME, MESSAGE_TIME.plusSeconds(1));
        verify(taskMapper).markTaskDone(eq("task-1"), eq("lease-1"), any());
        verify(taskMapper).markTaskDone(eq("task-2"), eq("lease-2"), any());
    }

    @Test
    void taskCompletionConflictKeepsStableLeaseError() {
        when(taskMapper.selectTaskIdentity("task-1")).thenReturn(task());
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session());
        when(taskMapper.lockOwnedTask(eq("task-1"), eq("lease-1"), any())).thenReturn(task());
        when(messageMapper.selectOne(any(Wrapper.class)))
                .thenReturn(message("m-1", 1L, MESSAGE_TIME));
        when(taskMapper.markTaskDone(eq("task-1"), eq("lease-1"), any())).thenReturn(0);

        assertThatThrownBy(() -> service.commitOnce("task-1", "lease-1", List.of(candidate())))
                .isInstanceOf(ChatMemoryCommitException.class)
                .extracting("code")
                .isEqualTo("MEMORY_LEASE_LOST");

        verify(lifecycleWriter).writeBatch(any(), any());
    }

    private ChatMemoryCommitRequest request(
            ChatMemoryExtractionTaskDO task,
            ChatMemoryCandidate candidate) {
        return new ChatMemoryCommitRequest(
                task.getTaskId(), task.getLeaseToken(), List.of(candidate));
    }

    private ChatMemoryExtractionTaskDO task(
            String taskId,
            String leaseToken,
            String messageId,
            Long messagePk) {
        ChatMemoryExtractionTaskDO task = task();
        task.setTaskId(taskId);
        task.setLeaseToken(leaseToken);
        task.setSourceMessageId(messageId);
        task.setSourceMessagePk(messagePk);
        return task;
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

    private ChatSessionDO session() {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId("session-1");
        session.setUserId("user-1");
        session.setDeleted(false);
        return session;
    }

    private ChatMessageDO message(String messageId, Long messagePk, LocalDateTime createdAt) {
        ChatMessageDO message = new ChatMessageDO();
        message.setId(messagePk);
        message.setMessageId(messageId);
        message.setSessionId("session-1");
        message.setRole("user");
        message.setContent("hello");
        message.setDeleted(false);
        message.setCreatedAt(createdAt);
        return message;
    }

    private ChatMemoryCandidate candidate(String messageId, String content) {
        return new ChatMemoryCandidate(
                ChatMemoryCategory.FACT,
                ChatMemorySubjectKey.parse(ChatMemoryCategory.FACT, "fact.test"),
                content,
                new ChatMemorySourceQuote(messageId, 0, 5, "hello"));
    }

    private ChatMemoryCandidate candidate() {
        return candidate("m-1", "x");
    }
}
