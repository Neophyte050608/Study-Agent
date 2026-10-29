package io.github.imzmq.interview.chat.application.contract;

import io.github.imzmq.interview.chat.application.contract.result.ChatMemoryRelationView;
import io.github.imzmq.interview.chat.application.contract.result.ChatMemoryView;

import java.util.List;

/**
 * Current-user operations for structured Chat memory lifecycle governance.
 */
public interface ChatMemoryOperations {

    List<ChatMemoryView> list(String userId, String status, int limit, Long beforeId);

    List<ChatMemoryRelationView> evolution(String userId, long memoryId);

    void confirmReplacement(String userId, long memoryId, long expectedVersion);

    void confirmCoexistence(String userId, long memoryId, long expectedVersion);

    void delete(String userId, long memoryId, long expectedVersion);
}
