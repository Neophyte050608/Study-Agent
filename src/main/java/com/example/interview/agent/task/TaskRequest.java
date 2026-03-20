package com.example.interview.agent.task;

import java.util.Map;

public record TaskRequest(
        TaskType taskType,
        Map<String, Object> payload,
        Map<String, Object> context
) {
}
