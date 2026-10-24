package io.github.imzmq.interview.chat.application.contract;

import io.github.imzmq.interview.agent.application.contract.task.TaskResponse;

public interface TaskResponsePresentationOperations {

    enum PresentationChannel {
        WEB,
        IM
    }

    String format(TaskResponse response, PresentationChannel channel);
}
