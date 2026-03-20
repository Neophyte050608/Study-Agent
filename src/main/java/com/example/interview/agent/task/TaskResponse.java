package com.example.interview.agent.task;

public record TaskResponse(
        boolean success,
        String message,
        Object data
) {
    public static TaskResponse ok(Object data) {
        return new TaskResponse(true, "ok", data);
    }

    public static TaskResponse fail(String message) {
        return new TaskResponse(false, message, null);
    }
}
