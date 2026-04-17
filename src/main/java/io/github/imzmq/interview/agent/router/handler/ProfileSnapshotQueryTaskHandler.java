package io.github.imzmq.interview.agent.router.handler;

import io.github.imzmq.interview.agent.router.TaskHandler;
import io.github.imzmq.interview.agent.router.TaskHandlerSupport;
import io.github.imzmq.interview.agent.task.TaskRequest;
import io.github.imzmq.interview.agent.task.TaskType;
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
        return learningProfileAgent.snapshot(TaskHandlerSupport.resolveUserId(request, learningProfileAgent));
    }
}


