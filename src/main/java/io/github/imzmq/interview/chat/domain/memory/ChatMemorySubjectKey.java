package io.github.imzmq.interview.chat.domain.memory;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ChatMemorySubjectKey {

    private static final int MIN_SEGMENTS = 2;
    private static final int MAX_SEGMENTS = 6;
    private static final int MAX_LENGTH = 191;
    private static final Pattern SEGMENT = Pattern.compile("[a-z0-9_]+");

    private final String value;

    private ChatMemorySubjectKey(String value) {
        this.value = value;
    }

    public static ChatMemorySubjectKey parse(ChatMemoryCategory category, String value) {
        Objects.requireNonNull(category, "category");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("memory subject key must not be blank");
        }

        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("memory subject key exceeds 191 characters");
        }

        String[] segments = normalized.split("\\.", -1);
        if (segments.length < MIN_SEGMENTS || segments.length > MAX_SEGMENTS) {
            throw new IllegalArgumentException("memory subject key must contain 2 to 6 segments");
        }
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("memory subject key contains an invalid segment");
            }
        }

        String expectedCategory = category.name().toLowerCase(Locale.ROOT);
        if (!expectedCategory.equals(segments[0])) {
            throw new IllegalArgumentException("memory subject key category does not match");
        }
        return new ChatMemorySubjectKey(normalized);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ChatMemorySubjectKey that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
