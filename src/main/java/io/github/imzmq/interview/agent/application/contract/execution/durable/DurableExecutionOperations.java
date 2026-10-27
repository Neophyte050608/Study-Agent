package io.github.imzmq.interview.agent.application.contract.execution.durable;
import java.util.Set;
public interface DurableExecutionOperations {
    DurableTaskView submit(String owner, String type, int version, String key, String inputJson);
    DurableTaskView query(String owner, String taskId);
    DurableTaskView retry(String owner, String taskId, int expectedRevision, Set<String> nodes);
    void delete(String owner, String taskId);
}
