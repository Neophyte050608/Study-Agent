package com.example.interview.agent.router.handler;

import com.example.interview.agent.CodingPracticeAgent;
import com.example.interview.agent.router.TaskHandler;
import com.example.interview.agent.router.TaskHandlerSupport;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskType;
import com.example.interview.service.LearningProfileAgent;
import org.springframework.stereotype.Component;

@Component
public class CodingPracticeTaskHandler implements TaskHandler {

    private final CodingPracticeAgent codingPracticeAgent;
    private final LearningProfileAgent learningProfileAgent;

    public CodingPracticeTaskHandler(CodingPracticeAgent codingPracticeAgent,
                                     LearningProfileAgent learningProfileAgent) {
        this.codingPracticeAgent = codingPracticeAgent;
        this.learningProfileAgent = learningProfileAgent;
    }

    @Override
    public TaskType taskType() {
        return TaskType.CODING_PRACTICE;
    }

    @Override
    public String receiverName() {
        return "CodingAgent";
    }

    @Override
    public Object handle(TaskRequest request) {
        return codingPracticeAgent.execute(TaskHandlerSupport.enrichCodingPayload(request, learningProfileAgent));
    }
}
