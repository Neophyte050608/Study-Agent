package com.example.interview.stream;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class InterviewStreamTaskManager {
    private final ConcurrentHashMap<String, StreamTask> tasks = new ConcurrentHashMap<>();

    public String newTaskId() {
        return "stream-" + UUID.randomUUID();
    }

    public void register(String taskId, InterviewSseEmitterSender sender) {
        tasks.put(taskId, new StreamTask(sender));
    }

    public void bindLifecycle(String taskId, SseEmitter emitter) {
        emitter.onCompletion(() -> unregister(taskId));
        emitter.onTimeout(() -> unregister(taskId));
        emitter.onError(ex -> unregister(taskId));
    }

    public boolean isCancelled(String taskId) {
        StreamTask task = tasks.get(taskId);
        return task == null || task.cancelled.get();
    }

    public boolean cancel(String taskId, String message) {
        StreamTask task = tasks.remove(taskId);
        if (task == null) {
            return false;
        }
        task.cancelled.set(true);
        task.sender.sendEvent(InterviewStreamEventType.CANCEL.value(), Map.of(
                "streamTaskId", taskId,
                "message", message == null || message.isBlank() ? "已停止生成" : message
        ));
        task.sender.sendEvent(InterviewStreamEventType.DONE.value(), "[DONE]");
        task.sender.complete();
        return true;
    }

    public void unregister(String taskId) {
        tasks.remove(taskId);
    }

    private static final class StreamTask {
        private final InterviewSseEmitterSender sender;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private StreamTask(InterviewSseEmitterSender sender) {
            this.sender = sender;
        }
    }
}
