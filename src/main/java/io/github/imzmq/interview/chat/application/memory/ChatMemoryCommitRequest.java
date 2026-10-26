package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCandidate;

import java.util.List;

public record ChatMemoryCommitRequest(
        String taskId,
        String leaseToken,
        List<ChatMemoryCandidate> candidates
) {
    public ChatMemoryCommitRequest {
        candidates = List.copyOf(candidates);
    }
}
