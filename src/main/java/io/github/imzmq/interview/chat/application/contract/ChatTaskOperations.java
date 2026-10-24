package io.github.imzmq.interview.chat.application.contract;

import io.github.imzmq.interview.agent.application.contract.task.TaskRequest;
import io.github.imzmq.interview.agent.application.contract.task.TaskResponse;

public interface ChatTaskOperations {
    TaskResponse dispatch(TaskRequest request);

    String extractReplyText(TaskResponse response);

    boolean shouldClearSession(TaskResponse response);
}
