package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCardinality;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryQuarantineReason;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static io.github.imzmq.interview.chat.application.memory.ChatMemoryLifecyclePolicy.Action.ACTIVATE;
import static io.github.imzmq.interview.chat.application.memory.ChatMemoryLifecyclePolicy.Action.ATTACH_SOURCE;
import static io.github.imzmq.interview.chat.application.memory.ChatMemoryLifecyclePolicy.Action.QUARANTINE;
import static io.github.imzmq.interview.chat.application.memory.ChatMemoryLifecyclePolicy.Action.SUPERSEDE;
import static org.assertj.core.api.Assertions.assertThat;

class ChatMemoryLifecyclePolicyTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 29, 10, 0);
    private final ChatMemoryLifecyclePolicy policy = new ChatMemoryLifecyclePolicy();

    @Test
    void newerSingletonSupersedesCurrentAndOlderSingletonIsQuarantined() {
        ChatMemoryLifecyclePolicy.CurrentItem current = current(
                10L, "old", ChatMemoryCardinality.SINGLE, BASE, 10L, 0);

        assertThat(policy.decide(request("new", ChatMemoryCardinality.SINGLE,
                        BASE.plusSeconds(1), 11L, 0, false), List.of(current)))
                .satisfies(decision -> {
                    assertThat(decision.action()).isEqualTo(SUPERSEDE);
                    assertThat(decision.quarantineReason()).isNull();
                    assertThat(decision.relatedMemoryIds()).containsExactly(10L);
                });
        assertThat(policy.decide(request("older", ChatMemoryCardinality.SINGLE,
                        BASE.minusSeconds(1), 9L, 0, false), List.of(current)))
                .satisfies(decision -> {
                    assertThat(decision.action()).isEqualTo(QUARANTINE);
                    assertThat(decision.quarantineReason())
                            .isEqualTo(ChatMemoryQuarantineReason.OLDER_THAN_CURRENT);
                    assertThat(decision.relatedMemoryIds()).containsExactly(10L);
                });
    }

    @Test
    void multiValueActivatesDifferentContentAndAttachesSameContent() {
        ChatMemoryLifecyclePolicy.CurrentItem current = current(
                10L, "same", ChatMemoryCardinality.MULTI, BASE, 10L, 0);

        assertThat(policy.decide(request("different", ChatMemoryCardinality.MULTI,
                        BASE.plusSeconds(1), 11L, 0, false), List.of(current)).action())
                .isEqualTo(ACTIVATE);
        assertThat(policy.decide(request("same", ChatMemoryCardinality.MULTI,
                        BASE.plusSeconds(1), 11L, 0, false), List.of(current)))
                .satisfies(decision -> {
                    assertThat(decision.action()).isEqualTo(ATTACH_SOURCE);
                    assertThat(decision.relatedMemoryIds()).containsExactly(10L);
                });
    }

    @Test
    void quarantinesBrokenSingletonAndCardinalityConflictDeterministically() {
        List<ChatMemoryLifecyclePolicy.CurrentItem> duplicateSingletons = List.of(
                current(12L, "two", ChatMemoryCardinality.SINGLE, BASE, 12L, 0),
                current(10L, "one", ChatMemoryCardinality.SINGLE, BASE, 10L, 0));

        assertThat(policy.decide(request("new", ChatMemoryCardinality.SINGLE,
                        BASE.plusSeconds(1), 13L, 0, false), duplicateSingletons))
                .satisfies(decision -> {
                    assertThat(decision.action()).isEqualTo(QUARANTINE);
                    assertThat(decision.quarantineReason())
                            .isEqualTo(ChatMemoryQuarantineReason.MULTIPLE_ACTIVE_SINGLETONS);
                    assertThat(decision.relatedMemoryIds()).containsExactly(10L, 12L);
                });

        assertThat(policy.decide(request("new", ChatMemoryCardinality.SINGLE,
                        BASE.plusSeconds(1), 13L, 0, false),
                List.of(current(10L, "old", ChatMemoryCardinality.MULTI, BASE, 10L, 0))))
                .satisfies(decision -> {
                    assertThat(decision.action()).isEqualTo(QUARANTINE);
                    assertThat(decision.quarantineReason())
                            .isEqualTo(ChatMemoryQuarantineReason.SUBJECT_CARDINALITY_CONFLICT);
                    assertThat(decision.relatedMemoryIds()).containsExactly(10L);
                });
    }

    @Test
    void batchAmbiguityOverridesOtherwiseValidSingletonDecision() {
        ChatMemoryLifecyclePolicy.Decision decision = policy.decide(
                request("new", ChatMemoryCardinality.SINGLE,
                        BASE.plusSeconds(1), 11L, 0, true),
                List.of(current(10L, "old", ChatMemoryCardinality.SINGLE, BASE, 10L, 0)));

        assertThat(decision.action()).isEqualTo(QUARANTINE);
        assertThat(decision.quarantineReason())
                .isEqualTo(ChatMemoryQuarantineReason.AMBIGUOUS_BATCH_ORDER);
        assertThat(decision.relatedMemoryIds()).containsExactly(10L);
    }

    @Test
    void historicalRowsDoNotBlockARepeatingContentFromCreatingANewNode() {
        ChatMemoryLifecyclePolicy.CurrentItem historical = new ChatMemoryLifecyclePolicy.CurrentItem(
                10L, "same", ChatMemoryStatus.SUPERSEDED, ChatMemoryCardinality.SINGLE,
                BASE, 10L, 0, 2L);

        assertThat(policy.decide(request("same", ChatMemoryCardinality.SINGLE,
                        BASE.plusSeconds(1), 11L, 0, false), List.of(historical)).action())
                .isEqualTo(ACTIVATE);
    }

    private ChatMemoryLifecycleRequest request(
            String hash,
            ChatMemoryCardinality cardinality,
            LocalDateTime createdAt,
            long messagePk,
            int quoteStart,
            boolean ambiguous) {
        return new ChatMemoryLifecycleRequest(
                "user-1", "session-1", null, hash, cardinality,
                createdAt, messagePk, quoteStart, ambiguous);
    }

    private ChatMemoryLifecyclePolicy.CurrentItem current(
            long id,
            String hash,
            ChatMemoryCardinality cardinality,
            LocalDateTime createdAt,
            long messagePk,
            int quoteStart) {
        return new ChatMemoryLifecyclePolicy.CurrentItem(
                id, hash, ChatMemoryStatus.ACTIVE, cardinality,
                createdAt, messagePk, quoteStart, 1L);
    }
}
