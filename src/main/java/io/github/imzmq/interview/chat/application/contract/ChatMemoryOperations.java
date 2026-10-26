package io.github.imzmq.interview.chat.application.contract;

import io.github.imzmq.interview.chat.application.contract.result.ChatMemoryView;

import java.util.List;

/**
 * Current-user operations for structured Chat memory.
 */
public interface ChatMemoryOperations {

    List<ChatMemoryView> list(String userId, int limit, Long beforeId);

    void delete(String userId, long memoryId);
}
