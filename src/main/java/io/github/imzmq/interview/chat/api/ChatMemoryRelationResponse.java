package io.github.imzmq.interview.chat.api;

import io.github.imzmq.interview.chat.application.contract.result.ChatMemoryRelationView;

import java.time.Instant;

/**
 * HTTP representation of a structured-memory lifecycle relation.
 */
public record ChatMemoryRelationResponse(
        long fromMemoryId,
        long toMemoryId,
        String relationType,
        Instant createdAt
) {

    static ChatMemoryRelationResponse from(ChatMemoryRelationView view) {
        return new ChatMemoryRelationResponse(
                view.fromMemoryId(), view.toMemoryId(), view.relationType(), view.createdAt());
    }
}
