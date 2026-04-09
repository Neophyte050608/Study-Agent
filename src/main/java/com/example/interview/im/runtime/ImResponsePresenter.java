package com.example.interview.im.runtime;

import com.example.interview.agent.task.TaskResponse;
import com.example.interview.service.TaskResponsePresentationService;
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
