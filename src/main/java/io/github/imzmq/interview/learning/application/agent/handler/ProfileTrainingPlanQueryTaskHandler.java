package io.github.imzmq.interview.learning.application.agent.handler;

import io.github.imzmq.interview.agent.application.contract.extension.TaskHandler;
import io.github.imzmq.interview.agent.application.contract.task.TaskRequest;
import io.github.imzmq.interview.agent.application.contract.task.TaskType;
import io.github.imzmq.interview.learning.application.LearningProfileAgent;
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
        String mode = LearningTaskHandlerSupport.readText(request.payload(), "mode");
        String userId = LearningTaskHandlerSupport.resolveUserId(request, learningProfileAgent);
        return Map.of(
                "agent", "LearningProfileAgent",
                "mode", mode.isBlank() ? "interview" : mode.toLowerCase(),
                "recommendation", learningProfileAgent.recommend(userId, mode)
        );
    }
}


