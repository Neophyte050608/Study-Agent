package io.github.imzmq.interview.agent.application.contract;

import io.github.imzmq.interview.agent.application.contract.task.TaskResponse;
import io.github.imzmq.interview.agent.application.contract.task.TaskType;

import java.util.Map;

/** Public entry for executing a task through the generic Agent runtime. */
public interface AgentTaskOperations {

    TaskResponse dispatchTask(TaskType taskType, Map<String, Object> payload, Map<String, Object> context);
}
