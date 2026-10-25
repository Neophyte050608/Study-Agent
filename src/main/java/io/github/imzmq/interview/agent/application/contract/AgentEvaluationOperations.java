package io.github.imzmq.interview.agent.application.contract;

import java.util.List;
import java.util.Map;

/** Operational management surface for agent evaluation trials. */
public interface AgentEvaluationOperations {

    Map<String, Object> runTrial(String operator, Map<String, Object> payload);

    List<Map<String, Object>> listTrials(int limit, String agent, String status);

    Map<String, Object> getTrial(String trialId);

    Map<String, Object> getTranscript(String trialId);

    List<Map<String, Object>> listScorers();
}
