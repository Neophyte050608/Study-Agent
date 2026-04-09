package com.example.interview.agent.router.handler;

import com.example.interview.agent.NoteMakingAgent;
import com.example.interview.agent.router.TaskHandler;
import com.example.interview.agent.router.TaskHandlerSupport;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskType;
import org.springframework.stereotype.Component;

@Component
public class LearningPlanTaskHandler implements TaskHandler {

    private final NoteMakingAgent noteMakingAgent;

    public LearningPlanTaskHandler(NoteMakingAgent noteMakingAgent) {
        this.noteMakingAgent = noteMakingAgent;
    }

    @Override
    public TaskType taskType() {
        return TaskType.LEARNING_PLAN;
    }

    @Override
    public String receiverName() {
        return "NoteAgent";
    }

    @Override
    public Object handle(TaskRequest request) {
        return noteMakingAgent.execute(TaskHandlerSupport.safePayload(request.payload()));
    }
}
