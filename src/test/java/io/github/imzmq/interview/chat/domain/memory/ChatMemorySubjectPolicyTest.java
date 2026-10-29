package io.github.imzmq.interview.chat.domain.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatMemorySubjectPolicyTest {

    @Test
    void assignsSingleCardinalityExceptForFactsByDefault() {
        assertThat(ChatMemorySubjectPolicy.cardinality(
                ChatMemoryCategory.PREFERENCE,
                key(ChatMemoryCategory.PREFERENCE, "preference.response_language")))
                .isEqualTo(ChatMemoryCardinality.SINGLE);
        assertThat(ChatMemorySubjectPolicy.cardinality(
                ChatMemoryCategory.GOAL,
                key(ChatMemoryCategory.GOAL, "goal.target_role")))
                .isEqualTo(ChatMemoryCardinality.SINGLE);
        assertThat(ChatMemorySubjectPolicy.cardinality(
                ChatMemoryCategory.CONSTRAINT,
                key(ChatMemoryCategory.CONSTRAINT, "constraint.available_study_time")))
                .isEqualTo(ChatMemoryCardinality.SINGLE);
        assertThat(ChatMemorySubjectPolicy.cardinality(
                ChatMemoryCategory.FACT,
                key(ChatMemoryCategory.FACT, "fact.known_language")))
                .isEqualTo(ChatMemoryCardinality.MULTI);
    }

    @ParameterizedTest
    @MethodSource("aliases")
    void canonicalizesOnlyTheConservativeAliasTable(
            ChatMemoryCategory category,
            String alias,
            String canonical) {
        ChatMemorySubjectKey result = ChatMemorySubjectPolicy.canonicalize(
                category, key(category, alias));

        assertThat(result.value()).isEqualTo(canonical);
    }

    @Test
    void leavesCanonicalKeysUnchangedAndRejectsCrossCategoryUse() {
        ChatMemorySubjectKey canonical = key(
                ChatMemoryCategory.PREFERENCE,
                "preference.response_language");

        assertThat(ChatMemorySubjectPolicy.canonicalize(
                ChatMemoryCategory.PREFERENCE, canonical)).isSameAs(canonical);
        assertThatThrownBy(() -> ChatMemorySubjectPolicy.canonicalize(
                ChatMemoryCategory.GOAL,
                key(ChatMemoryCategory.PREFERENCE, "preference.language")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> aliases() {
        return Stream.of(
                Arguments.of(ChatMemoryCategory.PREFERENCE,
                        "preference.language", "preference.response_language"),
                Arguments.of(ChatMemoryCategory.GOAL,
                        "goal.target_job", "goal.target_role"),
                Arguments.of(ChatMemoryCategory.CONSTRAINT,
                        "constraint.study_time", "constraint.available_study_time"),
                Arguments.of(ChatMemoryCategory.FACT,
                        "fact.city", "fact.current_city"));
    }

    private static ChatMemorySubjectKey key(ChatMemoryCategory category, String value) {
        return ChatMemorySubjectKey.parse(category, value);
    }
}
