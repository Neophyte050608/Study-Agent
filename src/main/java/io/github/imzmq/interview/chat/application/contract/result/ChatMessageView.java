package io.github.imzmq.interview.chat.application.contract.result;

import java.time.LocalDateTime;
import java.util.Map;

/** Stable chat-message result exposed outside the chat module. */
public record ChatMessageView(
        Long id,
        String messageId,
        String sessionId,
        String role,
        String contentType,
        String content,
        Map<String, Object> metadata,
        Boolean deleted,
        LocalDateTime createdAt
) {
}
