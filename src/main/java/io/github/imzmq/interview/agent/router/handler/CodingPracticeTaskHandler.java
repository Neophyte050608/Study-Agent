package io.github.imzmq.interview.agent.router.handler;

import io.github.imzmq.interview.agent.runtime.CodingPracticeAgent;
import io.github.imzmq.interview.agent.router.TaskHandler;
import io.github.imzmq.interview.agent.router.TaskHandlerSupport;
import io.github.imzmq.interview.agent.task.TaskRequest;
import io.github.imzmq.interview.agent.task.TaskType;
import io.github.imzmq.interview.learning.application.LearningProfileAgent;
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



