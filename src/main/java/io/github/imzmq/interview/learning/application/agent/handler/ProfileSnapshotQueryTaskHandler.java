package io.github.imzmq.interview.learning.application.agent.handler;

import io.github.imzmq.interview.agent.application.contract.extension.TaskHandler;
import io.github.imzmq.interview.agent.application.contract.task.TaskRequest;
import io.github.imzmq.interview.agent.application.contract.task.TaskType;
import io.github.imzmq.interview.learning.application.LearningProfileAgent;
import org.springframework.stereotype.Component;

@Component
public class ProfileSnapshotQueryTaskHandler implements TaskHandler {

    private final LearningProfileAgent learningProfileAgent;

    public ProfileSnapshotQueryTaskHandler(LearningProfileAgent learningProfileAgent) {
        this.learningProfileAgent = learningProfileAgent;
    }

    @Override
    public TaskType taskType() {
        return TaskType.PROFILE_SNAPSHOT_QUERY;
    }

    @Override
    public String receiverName() {
        return "LearningProfileAgent";
    }

    @Override
    public Object handle(TaskRequest request) {
        return learningProfileAgent.snapshot(LearningTaskHandlerSupport.resolveUserId(request, learningProfileAgent));
    }
}


