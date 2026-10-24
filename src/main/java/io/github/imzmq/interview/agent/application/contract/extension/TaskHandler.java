package io.github.imzmq.interview.agent.application.contract.extension;

import io.github.imzmq.interview.agent.application.contract.task.TaskRequest;
import io.github.imzmq.interview.agent.application.contract.task.TaskType;

public interface TaskHandler {

    TaskType taskType();

    String receiverName();

    Object handle(TaskRequest request);
}
