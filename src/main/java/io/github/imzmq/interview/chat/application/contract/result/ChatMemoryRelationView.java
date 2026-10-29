package io.github.imzmq.interview.chat.application.contract.result;

import java.time.Instant;

/**
 * Persistence-neutral lifecycle relation between two structured memories.
 */
public record ChatMemoryRelationView(
        long fromMemoryId,
        long toMemoryId,
        String relationType,
        Instant createdAt
) {
}
