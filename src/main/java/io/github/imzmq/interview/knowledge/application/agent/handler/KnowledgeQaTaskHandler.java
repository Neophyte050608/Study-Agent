package io.github.imzmq.interview.knowledge.application.agent.handler;

import io.github.imzmq.interview.knowledge.application.agent.KnowledgeQaAgent;
import io.github.imzmq.interview.agent.application.contract.extension.TaskHandler;
import io.github.imzmq.interview.agent.application.contract.task.TaskRequest;
import io.github.imzmq.interview.agent.application.contract.task.TaskType;
import io.github.imzmq.interview.knowledge.domain.KnowledgeRetrievalMode;
import org.springframework.stereotype.Component;

import java.util.Map;

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
                readText(request.payload(), "query"),
                readText(request.context(), "history"),
                resolveKnowledgeRetrievalMode(request),
                readText(request.context(), "sessionId"),
                request.context()
        );
    }

    private KnowledgeRetrievalMode resolveKnowledgeRetrievalMode(TaskRequest request) {
        return KnowledgeRetrievalMode.fromNullable(readText(request.context(), "retrievalMode"), null);
    }

    private String readText(Map<String, Object> values, String key) {
        if (values == null) {
            return "";
        }
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
