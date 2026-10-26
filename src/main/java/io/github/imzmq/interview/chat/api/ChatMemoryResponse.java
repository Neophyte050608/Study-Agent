package io.github.imzmq.interview.chat.api;

import io.github.imzmq.interview.chat.application.contract.result.ChatMemoryView;

import java.time.Instant;
import java.util.List;

/**
 * HTTP representation of structured Chat memory. Internal hashes and projection data are omitted.
 */
public record ChatMemoryResponse(
        long id,
        String category,
        String content,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<String> sourceMessageIds
) {

    static ChatMemoryResponse from(ChatMemoryView view) {
        return new ChatMemoryResponse(
                view.id(),
                view.category(),
                view.content(),
                view.status(),
                view.version(),
                view.createdAt(),
                view.updatedAt(),
                view.sourceMessageIds());
    }
}
