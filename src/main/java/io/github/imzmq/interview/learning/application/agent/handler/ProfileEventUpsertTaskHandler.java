package io.github.imzmq.interview.learning.application.agent.handler;

import io.github.imzmq.interview.agent.application.contract.extension.TaskHandler;
import io.github.imzmq.interview.agent.application.contract.task.TaskRequest;
import io.github.imzmq.interview.agent.application.contract.task.TaskType;
import io.github.imzmq.interview.learning.application.contract.LearningEvent;
import io.github.imzmq.interview.learning.application.LearningProfileAgent;
import io.github.imzmq.interview.learning.application.contract.LearningSource;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ProfileEventUpsertTaskHandler implements TaskHandler {

    private final LearningProfileAgent learningProfileAgent;

    public ProfileEventUpsertTaskHandler(LearningProfileAgent learningProfileAgent) {
        this.learningProfileAgent = learningProfileAgent;
    }

    @Override
    public TaskType taskType() {
        return TaskType.PROFILE_EVENT_UPSERT;
    }

    @Override
    public String receiverName() {
        return "LearningProfileAgent";
    }

    @Override
    public Object handle(TaskRequest request) {
        Integer score = LearningTaskHandlerSupport.readInt(request.payload(), "score");
        LearningEvent event = new LearningEvent(
                LearningTaskHandlerSupport.readText(request.payload(), "eventId"),
                LearningTaskHandlerSupport.resolveUserId(request, learningProfileAgent),
                parseSource(LearningTaskHandlerSupport.readText(request.payload(), "source")),
                LearningTaskHandlerSupport.readText(request.payload(), "topic"),
                score == null ? 60 : score,
                LearningTaskHandlerSupport.readTextList(request.payload(), "weakPoints"),
                LearningTaskHandlerSupport.readTextList(request.payload(), "familiarPoints"),
                LearningTaskHandlerSupport.readText(request.payload(), "evidence"),
                LearningTaskHandlerSupport.parseTimestamp(LearningTaskHandlerSupport.readText(request.payload(), "timestamp"))
        );
        boolean inserted = learningProfileAgent.upsertEvent(event);
        return Map.of("agent", "LearningProfileAgent", "status", inserted ? "inserted" : "duplicated", "eventId", event.eventId());
    }

    private LearningSource parseSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return LearningSource.INTERVIEW;
        }
        try {
            return LearningSource.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return LearningSource.INTERVIEW;
        }
    }
}


