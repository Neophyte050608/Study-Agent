package io.github.imzmq.interview.practice.application.agent;

import io.github.imzmq.interview.agent.application.contract.extension.TaskHandler;
import io.github.imzmq.interview.agent.application.contract.task.TaskRequest;
import io.github.imzmq.interview.agent.application.contract.task.TaskType;
import io.github.imzmq.interview.learning.application.LearningProfileAgent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

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
        return codingPracticeAgent.execute(enrichCodingPayload(request));
    }

    private Map<String, Object> enrichCodingPayload(TaskRequest request) {
        Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();
        Map<String, Object> merged = new LinkedHashMap<>(payload);
        if (!merged.containsKey("userId")) {
            merged.put("userId", resolveUserId(request));
        }
        if (!merged.containsKey("action")) {
            String query = readText(payload, "query");
            if (!query.isBlank()) {
                merged.put("action", "chat");
                merged.put("message", query);
            }
        }
        if (payload.containsKey("type")) {
            merged.put("type", payload.get("type"));
        }
        return merged;
    }

    private String resolveUserId(TaskRequest request) {
        String fromPayload = readText(request.payload(), "userId");
        if (!fromPayload.isBlank()) {
            return learningProfileAgent.normalizeUserId(fromPayload);
        }
        return learningProfileAgent.normalizeUserId(readText(request.context(), "userId"));
    }

    private String readText(Map<String, Object> values, String key) {
        if (values == null) {
            return "";
        }
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
