package com.example.interview.agent.router;

import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskType;

public interface TaskHandler {

    TaskType taskType();

    String receiverName();

    Object handle(TaskRequest request);
}
