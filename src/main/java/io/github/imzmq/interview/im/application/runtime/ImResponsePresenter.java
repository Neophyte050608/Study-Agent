package io.github.imzmq.interview.im.application.runtime;

import io.github.imzmq.interview.agent.application.contract.task.TaskResponse;
import io.github.imzmq.interview.chat.application.contract.TaskResponsePresentationOperations;
import org.springframework.stereotype.Component;

@Component
public class ImResponsePresenter {

    private final TaskResponsePresentationOperations taskResponsePresentationOperations;

    public ImResponsePresenter(TaskResponsePresentationOperations taskResponsePresentationOperations) {
        this.taskResponsePresentationOperations = taskResponsePresentationOperations;
    }

    public String format(TaskResponse response) {
        return taskResponsePresentationOperations.format(response, TaskResponsePresentationOperations.PresentationChannel.IM);
    }
}



