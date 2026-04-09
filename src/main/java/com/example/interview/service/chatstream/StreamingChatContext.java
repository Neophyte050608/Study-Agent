package com.example.interview.service.chatstream;

import com.example.interview.agent.task.TaskResponse;
import com.example.interview.service.KnowledgeRetrievalMode;
import com.example.interview.stream.InterviewSseEmitterSender;

public class StreamingChatContext {

    private final String sessionId;
    private final String userId;
    private final String content;
    private final KnowledgeRetrievalMode retrievalMode;
    private final String traceId;
    private final String taskId;
    private final InterviewSseEmitterSender sender;
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
                                InterviewSseEmitterSender sender) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.content = content;
        this.retrievalMode = retrievalMode;
        this.traceId = traceId;
        this.taskId = taskId;
        this.sender = sender;
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

    public InterviewSseEmitterSender sender() {
        return sender;
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
