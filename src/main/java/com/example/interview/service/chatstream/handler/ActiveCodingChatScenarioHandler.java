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
        if (activeCodingId == null || activeCodingId.isBlank() || ChatIntentHeuristics.looksLikeExplicitModeSwitch(context.content())) {
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
        Object nextQuizPayload = null;
        boolean nextQuizGenerated = false;
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
                    if (nextResponse.data() instanceof Map<?, ?> nextMap
                            && "batch_quiz".equals(nextMap.get("status"))
                            && nextMap.containsKey("quizPayload")) {
                        nextQuizPayload = nextMap.get("quizPayload");
                        nextQuizGenerated = true;
                    } else {
                        String nextText = webChatService.extractReplyText(nextResponse);
                        replyText = replyText + "\n\n---\n\n" + nextText;
                    }
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
        metadata.put("type", nextQuizGenerated ? "coding_practice_batch" : "coding_practice");
        metadata.put("routeLabel", nextQuizGenerated ? "coding-batch-quiz" : "coding-practice");
        metadata.put("routeSource", "active-session");
        if (!isLast) {
            metadata.put("codingSessionId", activeCodingId);
        }

        Map<String, Object> finishResult = new LinkedHashMap<>();
        finishResult.put("content", replyText);
        finishResult.put("traceId", context.traceId());
        finishResult.put("routeLabel", nextQuizGenerated ? "coding-batch-quiz" : "coding-practice");
        finishResult.put("routeSource", "active-session");

        if (nextQuizGenerated) {
            context.sender().sendEvent(InterviewStreamEventType.QUIZ.value(), nextQuizPayload);
            String quizJson = chatStreamingSupport.toJson(
                    nextQuizPayload,
                    "已为您生成批量选择题，请在答题卡中作答。"
            );
            chatStreamingSupport.updateAssistantPlaceholder(context.assistantMessageId(), quizJson, metadata, "quiz", "COMPLETED");
            finishResult.put("content", quizJson);
            finishResult.put("quizPayload", nextQuizPayload);
        } else {
            chatStreamingSupport.updateAssistantPlaceholder(context.assistantMessageId(), replyText, metadata, "text", "COMPLETED");
        }

        context.sender().sendEvent(InterviewStreamEventType.FINISH.value(), Map.of(
                "action", "chat",
                "result", finishResult));
        context.sender().sendEvent(InterviewStreamEventType.DONE.value(), "[DONE]");
        chatStreamingSupport.completeTask(context.taskId(), context.sender());
        return true;
    }
}
