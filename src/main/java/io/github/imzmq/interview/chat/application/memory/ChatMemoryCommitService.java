package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCandidate;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ChatMemoryCommitService {

    private static final int MAX_COMMIT_ATTEMPTS = 3;

    private final ChatMemoryCommitTransactionService transactionService;

    public ChatMemoryCommitService(ChatMemoryCommitTransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void commit(String taskId, String leaseToken, List<ChatMemoryCandidate> candidates) {
        commitBatch(List.of(new ChatMemoryCommitRequest(taskId, leaseToken, candidates)));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void commitBatch(List<ChatMemoryCommitRequest> requests) {
        List<ChatMemoryCommitRequest> immutableRequests = List.copyOf(requests);
        for (int attempt = 1; attempt <= MAX_COMMIT_ATTEMPTS; attempt++) {
            try {
                transactionService.commitBatchOnce(immutableRequests);
                return;
            } catch (ChatMemoryCommitException exception) {
                if (!"MEMORY_VERSION_CONFLICT".equals(exception.code())) {
                    throw exception;
                }
                if (attempt == MAX_COMMIT_ATTEMPTS) {
                    throw new ChatMemoryCommitException("MEMORY_WRITE_CONFLICT");
                }
            } catch (DuplicateKeyException | CannotAcquireLockException exception) {
                if (attempt == MAX_COMMIT_ATTEMPTS) {
                    throw new ChatMemoryCommitException("MEMORY_WRITE_CONFLICT");
                }
            }
        }
        throw new ChatMemoryCommitException("MEMORY_WRITE_CONFLICT");
    }
}
