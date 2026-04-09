package com.example.interview.agent.router.handler;

import com.example.interview.agent.router.TaskHandler;
import com.example.interview.agent.router.TaskHandlerSupport;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskType;
import com.example.interview.service.LearningEvent;
import com.example.interview.service.LearningProfileAgent;
import com.example.interview.service.LearningSource;
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
        Integer score = TaskHandlerSupport.readInt(request.payload(), "score");
        LearningEvent event = new LearningEvent(
                TaskHandlerSupport.readText(request.payload(), "eventId"),
                TaskHandlerSupport.resolveUserId(request, learningProfileAgent),
                parseSource(TaskHandlerSupport.readText(request.payload(), "source")),
                TaskHandlerSupport.readText(request.payload(), "topic"),
                score == null ? 60 : score,
                TaskHandlerSupport.readTextList(request.payload(), "weakPoints"),
                TaskHandlerSupport.readTextList(request.payload(), "familiarPoints"),
                TaskHandlerSupport.readText(request.payload(), "evidence"),
                TaskHandlerSupport.parseTimestamp(TaskHandlerSupport.readText(request.payload(), "timestamp"))
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
