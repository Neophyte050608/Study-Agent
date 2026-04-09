package com.example.interview.agent.router.handler;

import com.example.interview.agent.KnowledgeQaAgent;
import com.example.interview.agent.router.TaskHandler;
import com.example.interview.agent.router.TaskHandlerSupport;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskType;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeQaTaskHandler implements TaskHandler {

    private final KnowledgeQaAgent knowledgeQaAgent;

    public KnowledgeQaTaskHandler(KnowledgeQaAgent knowledgeQaAgent) {
        this.knowledgeQaAgent = knowledgeQaAgent;
    }

    @Override
    public TaskType taskType() {
        return TaskType.KNOWLEDGE_QA;
    }

    @Override
    public String receiverName() {
        return "KnowledgeQaAgent";
    }

    @Override
    public Object handle(TaskRequest request) {
        return knowledgeQaAgent.execute(
                TaskHandlerSupport.readText(request.payload(), "query"),
                TaskHandlerSupport.readText(request.context(), "history"),
                TaskHandlerSupport.resolveKnowledgeRetrievalMode(request),
                TaskHandlerSupport.readText(request.context(), "sessionId")
        );
    }
}
