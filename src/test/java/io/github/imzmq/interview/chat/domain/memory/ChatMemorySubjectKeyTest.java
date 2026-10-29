package io.github.imzmq.interview.chat.domain.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatMemorySubjectKeyTest {

    @Test
    void normalizesUnicodeAndCaseUsingTheCandidateCategory() {
        ChatMemorySubjectKey key = ChatMemorySubjectKey.parse(
                ChatMemoryCategory.PREFERENCE,
                "ＰＲＥＦＥＲＥＮＣＥ.Response_Language");

        assertThat(key.value()).isEqualTo("preference.response_language");
    }

    @Test
    void rejectsCategoryMismatchAndInvalidSegments() {
        assertThatThrownBy(() -> ChatMemorySubjectKey.parse(
                ChatMemoryCategory.GOAL,
                "preference.response_language"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChatMemorySubjectKey.parse(
                ChatMemoryCategory.FACT,
                "fact.current-city"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesSegmentAndLengthBounds() {
        assertThatThrownBy(() -> ChatMemorySubjectKey.parse(
                ChatMemoryCategory.FACT,
                "fact"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChatMemorySubjectKey.parse(
                ChatMemoryCategory.FACT,
                "fact.one.two.three.four.five.six"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChatMemorySubjectKey.parse(
                ChatMemoryCategory.FACT,
                "fact." + "x".repeat(187)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
