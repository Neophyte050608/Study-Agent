package io.github.imzmq.interview.agent.router.handler;

import io.github.imzmq.interview.agent.runtime.NoteMakingAgent;
import io.github.imzmq.interview.agent.router.TaskHandler;
import io.github.imzmq.interview.agent.router.TaskHandlerSupport;
import io.github.imzmq.interview.agent.task.TaskRequest;
import io.github.imzmq.interview.agent.task.TaskType;
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


