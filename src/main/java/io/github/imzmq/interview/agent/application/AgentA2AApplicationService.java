package io.github.imzmq.interview.agent.application;

import io.github.imzmq.interview.agent.a2a.A2ABus;
import io.github.imzmq.interview.agent.a2a.A2AIdempotencyStore;
import io.github.imzmq.interview.agent.a2a.RocketMqA2ABus;
import io.github.imzmq.interview.agent.application.contract.AgentA2AOperations;
import io.github.imzmq.interview.agent.application.contract.result.A2ABatchReplayResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AgentA2AApplicationService implements AgentA2AOperations {

    private final A2ABus a2aBus;
    private final A2AIdempotencyStore idempotencyStore;

    public AgentA2AApplicationService(A2ABus a2aBus, A2AIdempotencyStore idempotencyStore) {
        this.a2aBus = a2aBus;
        this.idempotencyStore = idempotencyStore;
    }

    @Override
    public Map<String, Object> idempotencySnapshot() {
        return idempotencyStore.snapshot();
    }

    @Override
    public Map<String, Object> clearIdempotency(String scope, String keyContains) {
        return idempotencyStore.clear(scope, keyContains);
    }

    @Override
    public boolean supportsDlqReplay() {
        return a2aBus instanceof RocketMqA2ABus;
    }

    @Override
    public boolean replayDlqMessage(String rawMessage) {
        return a2aBus instanceof RocketMqA2ABus rocketMqA2ABus
                && rocketMqA2ABus.dispatchInbound(rawMessage);
    }

    @Override
    public A2ABatchReplayResult replayDlqMessages(List<String> rawMessages) {
        List<String> messages = rawMessages == null ? List.of() : rawMessages;
        List<Integer> failedIndexes = new ArrayList<>();
        int success = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (replayDlqMessage(messages.get(i))) {
                success++;
            } else {
                failedIndexes.add(i);
            }
        }
        return new A2ABatchReplayResult(
                messages.size(), success, messages.size() - success, failedIndexes);
    }
}
