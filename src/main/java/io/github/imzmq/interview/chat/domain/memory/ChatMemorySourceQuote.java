package io.github.imzmq.interview.chat.domain.memory;

public record ChatMemorySourceQuote(String sourceMessageId, int start, int end, String quote) {

    public ChatMemorySourceQuote {
        if (sourceMessageId == null || sourceMessageId.isBlank()
                || start < 0 || end <= start || quote == null || quote.isEmpty()) {
            throw new IllegalArgumentException("invalid memory source quote");
        }
    }

    public boolean matches(String sourceContent) {
        try {
            return quote.equals(ChatMemoryNormalizer.codePointSlice(sourceContent, start, end));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
