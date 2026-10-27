package io.github.imzmq.interview.agent.application.contract.execution.durable;
import java.util.Map;
public record DurableTaskView(String taskId, String status, int revision, String resultJson,
                              String errorCode, Map<String, NodeView> nodes, String workflowType, int workflowVersion) {
    public DurableTaskView { nodes = Map.copyOf(nodes); }
    public record NodeView(String status, int attempts, String code) { }
}
