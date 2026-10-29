package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCandidate;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCardinality;

import java.time.LocalDateTime;

public record ChatMemoryLifecycleRequest(
        String userId,
        String sessionId,
        ChatMemoryCandidate candidate,
        String contentHash,
        ChatMemoryCardinality cardinality,
        LocalDateTime originMessageCreatedAt,
        long originMessagePk,
        int originQuoteStart,
        boolean ambiguousBatch
) {
}
