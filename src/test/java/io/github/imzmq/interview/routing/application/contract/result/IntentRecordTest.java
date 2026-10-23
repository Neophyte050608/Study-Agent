package io.github.imzmq.interview.routing.application.contract.result;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntentRecordTest {

    @Test
    void normalizesNullCollectionsAndCopiesMutableInputs() {
        Map<String, String> slots = new HashMap<>(Map.of("topic", "Redis"));
        IntentTask task = new IntentTask("KNOWLEDGE_QA", slots, 0.9);
        List<IntentTask> tasks = new ArrayList<>(List.of(task));

        IntentRecord record = new IntentRecord("s1", "那它呢", TurnRelation.FOLLOW_UP,
                "Redis 的持久化机制是什么", tasks, 0.8, "延续追问", DecisionSource.LLM);
        slots.put("extra", "ignored");
        tasks.clear();

        assertThat(record.tasks()).hasSize(1);
        assertThat(record.tasks().getFirst().slots()).containsExactly(Map.entry("topic", "Redis"));
        assertThatThrownBy(() -> record.tasks().add(task)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new IntentTask("CHITCHAT", null, 0.1).slots()).isEmpty();
    }

    @Test
    void rejectsInvalidConfidenceScoreAndRequiredEnums() {
        assertThatThrownBy(() -> new IntentTask("X", Map.of(), 1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntentRecord("s", "q", null, "q", List.of(),
                0.5, "", DecisionSource.LLM)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IntentRecord("s", "q", TurnRelation.NEW_TOPIC, "q", List.of(),
                -0.1, "", DecisionSource.LLM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntentRecord("s", "q", TurnRelation.NEW_TOPIC, "q", List.of(),
                0.5, "", null)).isInstanceOf(NullPointerException.class);
    }
}
