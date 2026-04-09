package com.example.interview.service.chatstream.handler;

import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.TaskType;
import com.example.interview.service.WebChatService;
import com.example.interview.service.chatstream.ChatIntentHeuristics;
import com.example.interview.service.chatstream.ChatScenarioHandler;
import com.example.interview.service.chatstream.ChatStreamingSupport;
import com.example.interview.service.chatstream.StreamingChatContext;
import com.example.interview.stream.InterviewStreamEventType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(20)
public class ActiveCodingChatScenarioHandler implements ChatScenarioHandler {

    private final WebChatService webChatService;
    private final ChatStreamingSupport chatStreamingSupport;

    public ActiveCodingChatScenarioHandler(WebChatService webChatService,
                                           ChatStreamingSupport chatStreamingSupport) {
        this.webChatService = webChatService;
        this.chatStreamingSupport = chatStreamingSupport;
    }

    @Override
    public boolean handle(StreamingChatContext context) {
        if (context.routeResponse() != null) {
            return false;
        }
        String activeCodingId = webChatService.findActiveCodingSessionId(context.sessionId());
        if (activeCodingId == null || activeCodingId.isBlank() || ChatIntentHeuristics.looksLikeNewIntent(context.content())) {
            return false;
        }

        Map<String, Object> codingPayload = new HashMap<>();
        codingPayload.put("action", "chat");
        codingPayload.put("sessionId", activeCodingId);
        codingPayload.put("message", context.content());
        codingPayload.put("userId", context.userId());
        TaskRequest codingRequest = new TaskRequest(TaskType.CODING_PRACTICE, codingPayload, Map.of(
                "sessionId", context.sessionId(),
                "userId", context.userId(),
                "history", context.history(),
                "traceId", context.traceId()
        ));
        TaskResponse codingResponse = webChatService.getTaskRouterAgent().dispatch(codingRequest);

        if (chatStreamingSupport.isCancelled(context.taskId())) {
            return true;
        }

        String replyText = webChatService.extractReplyText(codingResponse);
        boolean isLast = true;
        if (codingResponse.data() instanceof Map<?, ?> evalMap && "evaluated".equals(evalMap.get("status"))) {
            isLast = Boolean.TRUE.equals(evalMap.get("isLast"));
            if (!isLast) {
                Map<String, Object> nextPayload = new HashMap<>();
                nextPayload.put("action", "chat");
                nextPayload.put("sessionId", activeCodingId);
                nextPayload.put("message", "");
                nextPayload.put("userId", context.userId());
                TaskRequest nextRequest = new TaskRequest(TaskType.CODING_PRACTICE, nextPayload, Map.of(
                        "sessionId", context.sessionId(),
                        "userId", context.userId(),
                        "history", context.history(),
                        "traceId", context.traceId()
                ));
                TaskResponse nextResponse = webChatService.getTaskRouterAgent().dispatch(nextRequest);
                if (nextResponse.success()) {
                    String nextText = webChatService.extractReplyText(nextResponse);
                    replyText = replyText + "\n\n---\n\n" + nextText;
                }
            }
        }

        context.sender().sendEvent(InterviewStreamEventType.PROGRESS.value(), Map.of(
                "stage", "GENERATING", "label", "正在评估答案", "status", "running", "percent", 70));
        chatStreamingSupport.sendChunkedText(context.sender(), replyText, context.taskId());

        if (chatStreamingSupport.isCancelled(context.taskId())) {
            return true;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("traceId", context.traceId());
        metadata.put("type", "coding_practice");
        metadata.put("routeLabel", "coding-practice");
        metadata.put("routeSource", "active-session");
        if (!isLast) {
            metadata.put("codingSessionId", activeCodingId);
        }
        chatStreamingSupport.updateAssistantPlaceholder(context.assistantMessageId(), replyText, metadata, "text", "COMPLETED");

        context.sender().sendEvent(InterviewStreamEventType.FINISH.value(), Map.of(
                "action", "chat",
                "result", Map.of(
                        "content", replyText,
                        "traceId", context.traceId(),
                        "routeLabel", "coding-practice",
                        "routeSource", "active-session"
                )));
        context.sender().sendEvent(InterviewStreamEventType.DONE.value(), "[DONE]");
        chatStreamingSupport.completeTask(context.taskId(), context.sender());
        return true;
    }
}
