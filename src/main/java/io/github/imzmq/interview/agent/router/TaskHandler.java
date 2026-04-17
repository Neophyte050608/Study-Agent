package io.github.imzmq.interview.agent.router;

import io.github.imzmq.interview.agent.task.TaskRequest;
import io.github.imzmq.interview.agent.task.TaskType;

public interface TaskHandler {

    TaskType taskType();

    String receiverName();

    Object handle(TaskRequest request);
}

