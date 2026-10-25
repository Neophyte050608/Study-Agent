package io.github.imzmq.interview.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.a2a.A2ABus;
import io.github.imzmq.interview.agent.a2a.A2AIdempotencyStore;
import io.github.imzmq.interview.agent.a2a.A2AMessage;
import io.github.imzmq.interview.agent.a2a.RocketMqA2ABus;
import io.github.imzmq.interview.agent.application.contract.result.A2ABatchReplayResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class AgentA2AApplicationServiceTest {

    @Test
    void exposes_idempotency_operations_without_leaking_store() {
        A2AIdempotencyStore store = new A2AIdempotencyStore(300, 20_000);
        store.shouldProcess("task-1");
        AgentA2AApplicationService service = new AgentA2AApplicationService(new StubBus(), store);

        assertThat(service.idempotencySnapshot()).containsEntry("inMemorySize", 1);
        assertThat(service.clearIdempotency("memory", "task-1"))
                .containsEntry("clearedMemory", 1L);
    }

    @Test
    void reports_replay_as_unsupported_for_non_rocketmq_bus() {
        AgentA2AApplicationService service = new AgentA2AApplicationService(
                new StubBus(), new A2AIdempotencyStore(300, 20_000));

        assertThat(service.supportsDlqReplay()).isFalse();
        assertThat(service.replayDlqMessage("{}" )).isFalse();
        assertThat(service.replayDlqMessages(List.of("{}")))
                .isEqualTo(new A2ABatchReplayResult(1, 0, 1, List.of(0)));
    }

    @Test
    void replays_batch_and_reports_failed_indexes_for_rocketmq_bus() {
        RocketMqA2ABus bus = new RocketMqA2ABus(
                new StubBus(), null, new ObjectMapper(), "test", 0, 0) {
            @Override
            public boolean dispatchInbound(String rawMessage) {
                return rawMessage.startsWith("valid");
            }
        };
        AgentA2AApplicationService service = new AgentA2AApplicationService(
                bus, new A2AIdempotencyStore(300, 20_000));

        assertThat(service.supportsDlqReplay()).isTrue();
        assertThat(service.replayDlqMessages(List.of("valid-1", "invalid", "valid-2")))
                .isEqualTo(new A2ABatchReplayResult(3, 2, 1, List.of(1)));
    }

    private static class StubBus implements A2ABus {
        @Override
        public void publish(A2AMessage message) {
        }

        @Override
        public void subscribe(String receiver, Consumer<A2AMessage> handler) {
        }

        @Override
        public Optional<A2AMessage> requestReply(A2AMessage message) {
            return Optional.empty();
        }
    }
}
