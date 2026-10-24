package io.github.imzmq.interview.learning.application.agent.handler;

import io.github.imzmq.interview.learning.application.agent.NoteMakingAgent;
import io.github.imzmq.interview.agent.application.contract.extension.TaskHandler;
import io.github.imzmq.interview.agent.application.contract.task.TaskRequest;
import io.github.imzmq.interview.agent.application.contract.task.TaskType;
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
        return noteMakingAgent.execute(LearningTaskHandlerSupport.safePayload(request.payload()));
    }
}


