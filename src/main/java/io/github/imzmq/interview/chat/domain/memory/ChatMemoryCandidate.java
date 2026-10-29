package io.github.imzmq.interview.chat.domain.memory;

import java.util.Objects;

public record ChatMemoryCandidate(
        ChatMemoryCategory category,
        ChatMemorySubjectKey subjectKey,
        String content,
        ChatMemorySourceQuote source
) {
    private static final int MAX_CONTENT_CODE_POINTS = 500;

    public ChatMemoryCandidate {
        Objects.requireNonNull(category, "category");
        subjectKey = ChatMemorySubjectPolicy.canonicalize(
                category, Objects.requireNonNull(subjectKey, "subjectKey"));
        Objects.requireNonNull(source, "source");
        content = ChatMemoryNormalizer.normalize(content);
        if (content.isBlank()) {
            throw new IllegalArgumentException("memory content must not be blank");
        }
        if (content.codePointCount(0, content.length()) > MAX_CONTENT_CODE_POINTS) {
            throw new IllegalArgumentException("memory content exceeds 500 code points");
        }
    }
}
