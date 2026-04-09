package com.example.interview.agent.router.handler;

import com.example.interview.agent.router.TaskHandler;
import com.example.interview.agent.router.TaskHandlerSupport;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskType;
import com.example.interview.service.LearningProfileAgent;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ProfileTrainingPlanQueryTaskHandler implements TaskHandler {

    private final LearningProfileAgent learningProfileAgent;

    public ProfileTrainingPlanQueryTaskHandler(LearningProfileAgent learningProfileAgent) {
        this.learningProfileAgent = learningProfileAgent;
    }

    @Override
    public TaskType taskType() {
        return TaskType.PROFILE_TRAINING_PLAN_QUERY;
    }

    @Override
    public String receiverName() {
        return "LearningProfileAgent";
    }

    @Override
    public Object handle(TaskRequest request) {
        String mode = TaskHandlerSupport.readText(request.payload(), "mode");
        String userId = TaskHandlerSupport.resolveUserId(request, learningProfileAgent);
        return Map.of(
                "agent", "LearningProfileAgent",
                "mode", mode.isBlank() ? "interview" : mode.toLowerCase(),
                "recommendation", learningProfileAgent.recommend(userId, mode)
        );
    }
}
