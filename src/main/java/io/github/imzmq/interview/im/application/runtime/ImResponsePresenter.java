package io.github.imzmq.interview.im.application.runtime;

import io.github.imzmq.interview.agent.task.TaskResponse;
import io.github.imzmq.interview.interview.application.TaskResponsePresentationService;
import org.springframework.stereotype.Component;

@Component
public class ImResponsePresenter {

    private final TaskResponsePresentationService taskResponsePresentationService;

    public ImResponsePresenter(TaskResponsePresentationService taskResponsePresentationService) {
        this.taskResponsePresentationService = taskResponsePresentationService;
    }

    public String format(TaskResponse response) {
        return taskResponsePresentationService.format(response, TaskResponsePresentationService.PresentationChannel.IM);
    }
}



