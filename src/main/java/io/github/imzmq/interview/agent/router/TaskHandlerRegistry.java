package io.github.imzmq.interview.agent.router;

import io.github.imzmq.interview.agent.task.TaskType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class TaskHandlerRegistry {

    private final Map<TaskType, TaskHandler> handlers;

    public TaskHandlerRegistry(List<TaskHandler> handlers) {
        Map<TaskType, TaskHandler> mapped = new EnumMap<>(TaskType.class);
        if (handlers != null) {
            for (TaskHandler handler : handlers) {
                if (handler == null) {
                    continue;
                }
                TaskHandler previous = mapped.put(handler.taskType(), handler);
                if (previous != null) {
                    throw new IllegalStateException("duplicate task handler: " + handler.taskType());
                }
            }
        }
        this.handlers = Map.copyOf(mapped);
    }

    public TaskHandler require(TaskType taskType) {
        TaskHandler handler = handlers.get(taskType);
        if (handler == null) {
            throw new IllegalStateException("no task handler registered for " + taskType);
        }
        return handler;
    }
}

