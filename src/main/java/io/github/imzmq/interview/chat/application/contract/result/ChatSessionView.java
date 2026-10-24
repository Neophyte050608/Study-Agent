package io.github.imzmq.interview.chat.application.contract.result;

import java.time.LocalDateTime;

/** Stable chat-session result exposed outside the chat module. */
public record ChatSessionView(
        Long id,
        String sessionId,
        String userId,
        String title,
        String contextSummary,
        Long summaryUpToMsgId,
        Boolean deleted,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
