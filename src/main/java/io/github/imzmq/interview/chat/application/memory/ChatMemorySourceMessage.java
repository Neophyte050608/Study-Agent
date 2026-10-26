package io.github.imzmq.interview.chat.application.memory;

public record ChatMemorySourceMessage(
        Long id,
        String messageId,
        String userId,
        String sessionId,
        String role,
        boolean deleted,
        String content
) {
}
