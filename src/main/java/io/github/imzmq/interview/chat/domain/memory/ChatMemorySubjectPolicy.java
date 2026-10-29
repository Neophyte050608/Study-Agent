package io.github.imzmq.interview.chat.domain.memory;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ChatMemorySubjectPolicy {

    private static final Map<String, String> ALIASES = Map.of(
            "preference.language", "preference.response_language",
            "goal.target_job", "goal.target_role",
            "constraint.study_time", "constraint.available_study_time",
            "fact.city", "fact.current_city");
    private static final Set<String> SINGLETON_FACTS = Set.of();

    private ChatMemorySubjectPolicy() {
    }

    public static ChatMemorySubjectKey canonicalize(
            ChatMemoryCategory category,
            ChatMemorySubjectKey subjectKey) {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(subjectKey, "subjectKey");

        ChatMemorySubjectKey verified = ChatMemorySubjectKey.parse(category, subjectKey.value());
        String canonical = ALIASES.get(verified.value());
        if (canonical == null) {
            return subjectKey;
        }
        return ChatMemorySubjectKey.parse(category, canonical);
    }

    public static ChatMemoryCardinality cardinality(
            ChatMemoryCategory category,
            ChatMemorySubjectKey subjectKey) {
        ChatMemorySubjectKey canonical = canonicalize(category, subjectKey);
        return switch (category) {
            case PREFERENCE, GOAL, CONSTRAINT -> ChatMemoryCardinality.SINGLE;
            case FACT -> SINGLETON_FACTS.contains(canonical.value())
                    ? ChatMemoryCardinality.SINGLE
                    : ChatMemoryCardinality.MULTI;
        };
    }
}
