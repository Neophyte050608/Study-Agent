package io.github.imzmq.interview.chat.application.memory;

import java.time.LocalDateTime;

public record ChatMemorySourceMessage(
        Long id,
        String messageId,
        String userId,
        String sessionId,
        String role,
        boolean deleted,
        String content,
        LocalDateTime createdAt
) {
}
