package io.github.imzmq.interview.agent.application.contract;

import io.github.imzmq.interview.agent.application.contract.result.A2ABatchReplayResult;

import java.util.List;
import java.util.Map;

/** Public operational surface for A2A idempotency inspection and DLQ replay. */
public interface AgentA2AOperations {

    Map<String, Object> idempotencySnapshot();

    Map<String, Object> clearIdempotency(String scope, String keyContains);

    boolean supportsDlqReplay();

    boolean replayDlqMessage(String rawMessage);

    A2ABatchReplayResult replayDlqMessages(List<String> rawMessages);
}
