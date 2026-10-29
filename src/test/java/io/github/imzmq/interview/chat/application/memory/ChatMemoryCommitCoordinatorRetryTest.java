package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCandidate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ChatMemoryCommitCoordinatorRetryTest {

    private final ChatMemoryCommitTransactionService transactionService =
            mock(ChatMemoryCommitTransactionService.class);
    private final ChatMemoryCommitService service = new ChatMemoryCommitService(transactionService);
    private final List<ChatMemoryCandidate> candidates = List.of();

    @Test
    void coordinatorSuspendsCallerTransactionSoEachRetryGetsFreshBoundary() throws Exception {
        Method method = ChatMemoryCommitService.class.getMethod(
                "commit", String.class, String.class, List.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    void batchDelegatesToOneAtomicTransaction() {
        ChatMemoryCommitRequest request1 = new ChatMemoryCommitRequest("task-1", "lease-1", candidates);
        ChatMemoryCommitRequest request2 = new ChatMemoryCommitRequest("task-2", "lease-2", candidates);

        service.commitBatch(List.of(request1, request2));

        verify(transactionService).commitBatchOnce(List.of(request1, request2));
    }

    @Test
    void retriesCasConflictInFreshTransaction() {
        doThrow(new ChatMemoryCommitException("MEMORY_VERSION_CONFLICT"))
                .doNothing()
                .when(transactionService).commitBatchOnce(List.of(new ChatMemoryCommitRequest("task-1", "lease-1", candidates)));

        service.commit("task-1", "lease-1", candidates);

        verify(transactionService, times(2)).commitBatchOnce(
                List.of(new ChatMemoryCommitRequest("task-1", "lease-1", candidates)));
    }

    @Test
    void retriesUniqueKeyAndDeadlockConflictsOnly() {
        doThrow(new DuplicateKeyException("race"))
                .doThrow(new CannotAcquireLockException("deadlock"))
                .doNothing()
                .when(transactionService).commitBatchOnce(List.of(new ChatMemoryCommitRequest("task-1", "lease-1", candidates)));

        service.commit("task-1", "lease-1", candidates);

        verify(transactionService, times(3)).commitBatchOnce(
                List.of(new ChatMemoryCommitRequest("task-1", "lease-1", candidates)));
    }

    @Test
    void retriesTheDeadlockTranslationUsedByMySql() {
        doThrow(new DeadlockLoserDataAccessException("deadlock", null))
                .doNothing()
                .when(transactionService).commitBatchOnce(List.of(
                        new ChatMemoryCommitRequest("task-1", "lease-1", candidates)));

        service.commit("task-1", "lease-1", candidates);

        verify(transactionService, times(2)).commitBatchOnce(List.of(
                new ChatMemoryCommitRequest("task-1", "lease-1", candidates)));
    }

    @Test
    void validationFailureIsNotRetried() {
        doThrow(new ChatMemoryCommitException("MEMORY_SOURCE_INVALID"))
                .when(transactionService).commitBatchOnce(List.of(new ChatMemoryCommitRequest("task-1", "lease-1", candidates)));

        assertThatThrownBy(() -> service.commit("task-1", "lease-1", candidates))
                .isInstanceOf(ChatMemoryCommitException.class)
                .extracting("code")
                .isEqualTo("MEMORY_SOURCE_INVALID");
        verify(transactionService).commitBatchOnce(List.of(new ChatMemoryCommitRequest("task-1", "lease-1", candidates)));
    }

    @Test
    void exhaustedWriteConflictsBecomeControlledRetryCode() {
        doThrow(new DuplicateKeyException("race"))
                .when(transactionService).commitBatchOnce(List.of(new ChatMemoryCommitRequest("task-1", "lease-1", candidates)));

        assertThatThrownBy(() -> service.commit("task-1", "lease-1", candidates))
                .isInstanceOf(ChatMemoryCommitException.class)
                .extracting("code")
                .isEqualTo("MEMORY_WRITE_CONFLICT");
        verify(transactionService, times(3)).commitBatchOnce(
                List.of(new ChatMemoryCommitRequest("task-1", "lease-1", candidates)));
    }
}
