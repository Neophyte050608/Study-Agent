package com.example.interview.agent.router.handler;

import com.example.interview.agent.router.TaskHandler;
import com.example.interview.agent.router.TaskHandlerSupport;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskType;
import com.example.interview.service.LearningProfileAgent;
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
