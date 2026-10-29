package io.github.imzmq.interview.chat.application.contract.result;

import java.time.Instant;
import java.util.List;

/**
 * Persistence- and SDK-neutral view of one structured Chat memory.
 */
public record ChatMemoryView(
        long id,
        String category,
        String subjectKey,
        String subjectCardinality,
        String content,
        String status,
        String quarantineReason,
        long version,
        Instant validFrom,
        Instant validUntil,
        Instant createdAt,
        Instant updatedAt,
        List<String> sourceMessageIds
) {

    public ChatMemoryView {
        sourceMessageIds = List.copyOf(sourceMessageIds);
    }
}
