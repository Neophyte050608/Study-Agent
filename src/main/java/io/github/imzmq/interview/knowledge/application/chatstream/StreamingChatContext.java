package io.github.imzmq.interview.knowledge.application.chatstream;

import io.github.imzmq.interview.agent.task.TaskResponse;
import io.github.imzmq.interview.knowledge.domain.KnowledgeRetrievalMode;
import io.github.imzmq.interview.stream.runtime.StreamEventEmitter;

public class StreamingChatContext {

    private final String sessionId;
    private final String userId;
    private final String content;
    private final KnowledgeRetrievalMode retrievalMode;
    private final String traceId;
    private final String taskId;
    private final StreamEventEmitter emitter;
    private String assistantMessageId = "";
    private String history = "";
    private TaskResponse routeResponse;
    private String routeSource = "";

    public StreamingChatContext(String sessionId,
                                String userId,
                                String content,
                                KnowledgeRetrievalMode retrievalMode,
                                String traceId,
                                String taskId,
                                StreamEventEmitter emitter) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.content = content;
        this.retrievalMode = retrievalMode;
        this.traceId = traceId;
        this.taskId = taskId;
        this.emitter = emitter;
    }

    public String sessionId() {
        return sessionId;
    }

    public String userId() {
        return userId;
    }

    public String content() {
        return content;
    }

    public KnowledgeRetrievalMode retrievalMode() {
        return retrievalMode;
    }

    public String traceId() {
        return traceId;
    }

    public String taskId() {
        return taskId;
    }

    public StreamEventEmitter emitter() {
        return emitter;
    }

    public String assistantMessageId() {
        return assistantMessageId;
    }

    public void assistantMessageId(String assistantMessageId) {
        this.assistantMessageId = assistantMessageId == null ? "" : assistantMessageId;
    }

    public String history() {
        return history;
    }

    public void history(String history) {
        this.history = history == null ? "" : history;
    }

    public TaskResponse routeResponse() {
        return routeResponse;
    }

    public void routeResponse(TaskResponse routeResponse) {
        this.routeResponse = routeResponse;
    }

    public String routeSource() {
        return routeSource;
    }

    public void routeSource(String routeSource) {
        this.routeSource = routeSource == null ? "" : routeSource;
    }
}




