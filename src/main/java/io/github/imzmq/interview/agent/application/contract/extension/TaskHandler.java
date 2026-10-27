package io.github.imzmq.interview.agent.application.contract.extension;

import io.github.imzmq.interview.agent.application.contract.task.TaskRequest;
import io.github.imzmq.interview.agent.application.contract.task.TaskType;

public interface TaskHandler {

    TaskType taskType();

    String receiverName();

    /** Returns legacy business data, or an explicit TaskResponse to preserve failure state and partial results. */
    Object handle(TaskRequest request);
}
