package io.github.imzmq.interview.stream.runtime;

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

    public void register(String taskId, StreamEventEmitter emitter) {
        tasks.put(taskId, new StreamTask(emitter));
    }

    public void bindLifecycle(String taskId, SseEmitter emitter) {
        emitter.onCompletion(() -> detach(taskId));
        emitter.onTimeout(() -> detach(taskId));
        emitter.onError(ex -> detach(taskId));
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
        StreamEventEmitter emitter = task.emitter;
        if (emitter != null) {
            emitter.emit(InterviewStreamEventType.CANCEL.value(), Map.of(
                    "streamTaskId", taskId,
                    "message", message == null || message.isBlank() ? "已停止生成" : message
            ));
            emitter.done();
            emitter.complete();
        }
        return true;
    }

    public void attachMessage(String taskId, String messageId) {
        StreamTask task = tasks.get(taskId);
        if (task != null) {
            task.messageId = messageId;
        }
    }

    public String getMessageId(String taskId) {
        StreamTask task = tasks.get(taskId);
        return task == null ? "" : task.messageId;
    }

    public void unregister(String taskId) {
        tasks.remove(taskId);
    }

    public void detach(String taskId) {
        StreamTask task = tasks.get(taskId);
        if (task != null) {
            task.emitter = null;
        }
    }

    private static final class StreamTask {
        private volatile StreamEventEmitter emitter;
        private volatile String messageId = "";
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private StreamTask(StreamEventEmitter emitter) {
            this.emitter = emitter;
        }
    }
}

