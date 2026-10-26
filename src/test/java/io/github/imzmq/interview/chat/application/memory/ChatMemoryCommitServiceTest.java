package io.github.imzmq.interview.chat.application.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCandidate;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCategory;
import io.github.imzmq.interview.chat.domain.memory.ChatMemorySourceQuote;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryNormalizer;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryOutboxMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryCommitServiceTest {

    private final ChatMemoryExtractionTaskMapper taskMapper = mock(ChatMemoryExtractionTaskMapper.class);
    private final ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
    private final ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
    private final ChatMemoryItemMapper itemMapper = mock(ChatMemoryItemMapper.class);
    private final ChatMemorySourceMapper sourceMapper = mock(ChatMemorySourceMapper.class);
    private final ChatMemoryOutboxMapper outboxMapper = mock(ChatMemoryOutboxMapper.class);
    private final ChatMemoryCommitTransactionService service = new ChatMemoryCommitTransactionService(
            taskMapper, sessionMapper, messageMapper, itemMapper, sourceMapper,
            new ChatMemoryOutboxEventService(outboxMapper));

    @Test
    void commitBoundaryIsTransactional() throws Exception {
        Method method = ChatMemoryCommitTransactionService.class.getMethod(
                "commitOnce", String.class, String.class, List.class);
        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void lostLeaseRejectsCommitWithoutWrites() {
        when(taskMapper.selectTaskIdentity("task-1")).thenReturn(task());
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session());
        when(taskMapper.lockOwnedTask(eq("task-1"), eq("stale"), any())).thenReturn(null);

        assertThatThrownBy(() -> service.commitOnce("task-1", "stale", List.of(candidate())))
                .isInstanceOf(ChatMemoryCommitException.class)
                .hasMessageContaining("MEMORY_LEASE_LOST");

        verify(itemMapper, never()).insert(any());
        verify(sourceMapper, never()).insert(any());
        verify(outboxMapper, never()).insert(any());
    }

    @Test
    void newFactCreatesItemSourceOutboxAndCompletesWithSameLease() {
        when(taskMapper.selectTaskIdentity("task-1")).thenReturn(task());
        when(taskMapper.lockOwnedTask(eq("task-1"), eq("lease-1"), any())).thenReturn(task());
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session());
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(message());
        when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(itemMapper.insert(any())).thenAnswer(invocation -> {
            ChatMemoryItemDO item = invocation.getArgument(0);
            item.setId(10L);
            return 1;
        });
        when(taskMapper.markTaskDone(eq("task-1"), eq("lease-1"), any())).thenReturn(1);

        service.commitOnce("task-1", "lease-1", List.of(candidate()));

        verify(itemMapper).insert(argThat(item ->
                "user-1".equals(item.getUserId()) && "ACTIVE".equals(item.getStatus())
                        && Long.valueOf(1L).equals(item.getVersion())));
        verify(sourceMapper).insert(argThat(source ->
                Long.valueOf(10L).equals(source.getMemoryId())
                        && "m-1".equals(source.getSourceMessageId())));
        verify(outboxMapper).insert(argThat(event ->
                Long.valueOf(10L).equals(event.getMemoryId())
                        && "user-1".equals(event.getUserId())
                        && "UPSERT".equals(event.getOperation())
                        && "PENDING".equals(event.getStatus())));
        verify(taskMapper).markTaskDone(eq("task-1"), eq("lease-1"), any());
    }

    @Test
    void locksSessionBeforeClaimedTask() {
        when(taskMapper.selectTaskIdentity("task-1")).thenReturn(task());
        when(taskMapper.lockOwnedTask(eq("task-1"), eq("lease-1"), any())).thenReturn(task());
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session());
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(message());
        when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(itemMapper.insert(any())).thenAnswer(invocation -> {
            ChatMemoryItemDO item = invocation.getArgument(0);
            item.setId(10L);
            return 1;
        });
        when(taskMapper.markTaskDone(eq("task-1"), eq("lease-1"), any())).thenReturn(1);

        service.commitOnce("task-1", "lease-1", List.of(candidate()));

        org.mockito.InOrder order = inOrder(sessionMapper, taskMapper);
        order.verify(sessionMapper).selectOne(any(Wrapper.class));
        order.verify(taskMapper).lockOwnedTask(eq("task-1"), eq("lease-1"), any());
    }

    @Test
    void batchLocksAllTasksBeforeMemoryRowsAndUsesGlobalHashOrder() {
        ChatMemoryExtractionTaskDO task1 = task("task-1", "lease-1", "m-1", 1L);
        ChatMemoryExtractionTaskDO task2 = task("task-2", "lease-2", "m-2", 2L);
        ChatMemoryCandidate candidate1 = candidate("m-1", "alpha");
        ChatMemoryCandidate candidate2 = candidate("m-2", "omega");
        List<ChatMemoryCandidate> candidatesByDescendingHash = new ArrayList<>(
                List.of(candidate1, candidate2));
        candidatesByDescendingHash.sort(Comparator.comparing(this::contentHash).reversed());
        ChatMemoryCandidate firstCandidate = candidatesByDescendingHash.get(0);
        ChatMemoryCandidate secondCandidate = candidatesByDescendingHash.get(1);
        ChatMemoryExtractionTaskDO firstTask = firstCandidate.source().sourceMessageId().equals("m-1")
                ? task1 : task2;
        ChatMemoryExtractionTaskDO secondTask = firstTask == task1 ? task2 : task1;

        when(taskMapper.selectTaskIdentity("task-1")).thenReturn(task1);
        when(taskMapper.selectTaskIdentity("task-2")).thenReturn(task2);
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session());
        List<String> events = new ArrayList<>();
        when(taskMapper.lockOwnedTask(eq("task-1"), eq("lease-1"), any()))
                .thenAnswer(invocation -> {
                    events.add("task-1");
                    return task1;
                });
        when(taskMapper.lockOwnedTask(eq("task-2"), eq("lease-2"), any()))
                .thenAnswer(invocation -> {
                    events.add("task-2");
                    return task2;
                });
        when(messageMapper.selectOne(any(Wrapper.class)))
                .thenReturn(message("m-1", 1L), message("m-2", 2L));
        when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        java.util.concurrent.atomic.AtomicLong memoryId =
                new java.util.concurrent.atomic.AtomicLong(10L);
        when(itemMapper.insert(any())).thenAnswer(invocation -> {
            ChatMemoryItemDO item = invocation.getArgument(0);
            events.add("memory-" + item.getContentHash());
            item.setId(memoryId.getAndIncrement());
            return 1;
        });
        when(taskMapper.markTaskDone(any(), any(), any())).thenReturn(1);

        service.commitBatchOnce(List.of(
                request(firstTask, firstCandidate),
                request(secondTask, secondCandidate)));

        assertThat(events).containsExactly(
                "task-1", "task-2",
                "memory-" + contentHash(secondCandidate),
                "memory-" + contentHash(firstCandidate));
    }

    @Test
    void deletedTombstoneIsNeverAutomaticallyReactivated() {
        when(taskMapper.selectTaskIdentity("task-1")).thenReturn(task());
        when(taskMapper.lockOwnedTask(eq("task-1"), eq("lease-1"), any())).thenReturn(task());
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session());
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(message());
        ChatMemoryItemDO deleted = new ChatMemoryItemDO();
        deleted.setId(10L);
        deleted.setStatus("DELETED");
        deleted.setVersion(2L);
        when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(deleted);
        when(taskMapper.markTaskDone(eq("task-1"), eq("lease-1"), any())).thenReturn(1);

        service.commitOnce("task-1", "lease-1", List.of(candidate()));

        verify(sourceMapper, never()).insert(any());
        verify(outboxMapper, never()).insert(any());
        verify(taskMapper).markTaskDone(eq("task-1"), eq("lease-1"), any());
    }

    private ChatMemoryCommitRequest request(ChatMemoryExtractionTaskDO task,
                                              ChatMemoryCandidate candidate) {
        return new ChatMemoryCommitRequest(
                task.getTaskId(), task.getLeaseToken(), List.of(candidate));
    }

    private String contentHash(ChatMemoryCandidate candidate) {
        return ChatMemoryNormalizer.contentHash(candidate.category(), candidate.content());
    }

    private ChatMemoryExtractionTaskDO task(String taskId, String leaseToken,
                                             String messageId, Long messagePk) {
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

    private ChatMessageDO message(String messageId, Long messagePk) {
        ChatMessageDO message = message();
        message.setId(messagePk);
        message.setMessageId(messageId);
        return message;
    }

    private ChatMessageDO message() {
        ChatMessageDO message = new ChatMessageDO();
        message.setId(1L);
        message.setMessageId("m-1");
        message.setSessionId("session-1");
        message.setRole("user");
        message.setContent("hello");
        message.setDeleted(false);
        return message;
    }

    private ChatMemoryCandidate candidate(String messageId, String content) {
        return new ChatMemoryCandidate(ChatMemoryCategory.FACT, content,
                new ChatMemorySourceQuote(messageId, 0, 5, "hello"));
    }

    private ChatMemoryCandidate candidate() {
        return new ChatMemoryCandidate(ChatMemoryCategory.FACT, "x",
                new ChatMemorySourceQuote("m-1", 0, 5, "hello"));
    }
}
