package io.github.imzmq.interview.chat.domain.memory;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatMemoryCandidateValidatorTest {

    @Test
    void exposesOnlyFourAllowedCategories() {
        assertThat(EnumSet.allOf(ChatMemoryCategory.class))
                .containsExactlyInAnyOrder(
                        ChatMemoryCategory.PREFERENCE,
                        ChatMemoryCategory.GOAL,
                        ChatMemoryCategory.FACT,
                        ChatMemoryCategory.CONSTRAINT);
    }

    @Test
    void acceptsExactQuoteAtUnicodeCodePointOffsets() {
        ChatMemorySourceQuote source = new ChatMemorySourceQuote(
                "message-1", 1, 4, "😀偏好");
        ChatMemoryCandidate candidate = new ChatMemoryCandidate(
                ChatMemoryCategory.PREFERENCE,
                ChatMemorySubjectKey.parse(
                        ChatMemoryCategory.PREFERENCE,
                        "preference.response_language"),
                "我偏好 Java",
                source);

        assertThat(candidate.source().matches("A😀偏好Java")).isTrue();
    }

    @Test
    void rejectsQuoteMismatchAndInvalidOffsets() {
        ChatMemorySourceQuote mismatch = new ChatMemorySourceQuote(
                "message-1", 1, 4, "偏好J");

        assertThat(mismatch.matches("A😀偏好Java")).isFalse();
        assertThatThrownBy(() -> new ChatMemorySourceQuote("message-1", -1, 2, "A"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ChatMemorySourceQuote("message-1", 1, 99, "A")
                .matches("short")).isFalse();
    }

    @Test
    void rejectsCandidateContentOverFiveHundredCodePoints() {
        String tooLong = "😀".repeat(501);

        assertThatThrownBy(() -> new ChatMemoryCandidate(
                ChatMemoryCategory.FACT,
                ChatMemorySubjectKey.parse(
                        ChatMemoryCategory.FACT,
                        "fact.known_language"),
                tooLong,
                new ChatMemorySourceQuote("message-1", 0, 1, "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
    }
}
