package io.github.imzmq.interview.agent.router.handler;

import io.github.imzmq.interview.agent.runtime.KnowledgeQaAgent;
import io.github.imzmq.interview.agent.router.TaskHandler;
import io.github.imzmq.interview.agent.router.TaskHandlerSupport;
import io.github.imzmq.interview.agent.task.TaskRequest;
import io.github.imzmq.interview.agent.task.TaskType;
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
                TaskHandlerSupport.readText(request.context(), "sessionId"),
                request.context()
        );
    }
}


