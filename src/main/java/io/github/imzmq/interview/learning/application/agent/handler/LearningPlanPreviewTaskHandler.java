package io.github.imzmq.interview.learning.application.agent.handler;

import io.github.imzmq.interview.agent.application.contract.extension.TaskHandler;
import io.github.imzmq.interview.agent.application.contract.task.*;
import io.github.imzmq.interview.learning.application.planning.LearningPlanPreviewService;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public final class LearningPlanPreviewTaskHandler implements TaskHandler {
    private final LearningPlanPreviewService service;

    public LearningPlanPreviewTaskHandler(LearningPlanPreviewService service) { this.service = service; }
    @Override public TaskType taskType() { return TaskType.LEARNING_PLAN_PREVIEW; }
    @Override public String receiverName() { return "LearningPlanPreview"; }

    @Override
    public Object handle(TaskRequest request) {
        // Trusted execution identity is context-owned; never allow payload to select another user's profile.
        var result = service.preview(text(request.context(), "userId"), text(request.payload(), "topic"),
                text(request.payload(), "query"));
        boolean success = "COMPLETED".equals(result.status()) || "DEGRADED".equals(result.status());
        return new TaskResponse(success, result.status(), result);
    }

    private String text(Map<String, Object> map, String key) {
        return map == null || map.get(key) == null ? "" : map.get(key).toString();
    }
}
