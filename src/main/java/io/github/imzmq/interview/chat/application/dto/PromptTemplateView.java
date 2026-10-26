package io.github.imzmq.interview.chat.application.dto;

import java.time.LocalDateTime;

/** Application view used by the prompt settings API without exposing persistence types. */
public record PromptTemplateView(
        Long id,
        String name,
        String category,
        String type,
        String title,
        String description,
        String content,
        Boolean isBuiltin,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
