package com.example.interview.service.chatstream.handler;

import com.example.interview.agent.KnowledgeQaAgent;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.service.WebChatService;
import com.example.interview.service.chatstream.ChatScenarioHandler;
import com.example.interview.service.chatstream.ChatStreamingSupport;
import com.example.interview.service.chatstream.StreamingChatContext;
import com.example.interview.stream.InterviewStreamEventType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(100)
public class KnowledgeQaChatScenarioHandler implements ChatScenarioHandler {

    private final KnowledgeQaAgent knowledgeQaAgent;
    private final WebChatService webChatService;
    private final ChatStreamingSupport chatStreamingSupport;

    public KnowledgeQaChatScenarioHandler(KnowledgeQaAgent knowledgeQaAgent,
                                          WebChatService webChatService,
                                          ChatStreamingSupport chatStreamingSupport) {
        this.knowledgeQaAgent = knowledgeQaAgent;
        this.webChatService = webChatService;
        this.chatStreamingSupport = chatStreamingSupport;
    }

    @Override
    public boolean handle(StreamingChatContext context) {
        TaskResponse response = context.routeResponse();
        if (response == null) {
            return false;
        }
        if (!isKnowledgeQaResult(response)) {
            return false;
        }

        context.sender().sendEvent(InterviewStreamEventType.PROGRESS.value(), Map.of(
                "stage", "RETRIEVING", "label", "正在检索知识", "status", "running", "percent", 50));
        context.sender().sendEvent(InterviewStreamEventType.PROGRESS.value(), Map.of(
                "stage", "GENERATING", "label", "正在生成回答", "status", "running", "percent", 70));

        StringBuilder fullAnswer = new StringBuilder();
        Map<String, Object> result = knowledgeQaAgent.executeStream(
                context.content(),
                context.history(),
                context.retrievalMode(),
                token -> {
                    if (!chatStreamingSupport.isCancelled(context.taskId())) {
                        fullAnswer.append(token);
                        context.sender().sendEvent(InterviewStreamEventType.MESSAGE.value(), Map.of(
                                "channel", "answer",
                                "delta", token));
                    }
                }
        );

        if (chatStreamingSupport.isCancelled(context.taskId())) {
            return true;
        }

        String replyText = fullAnswer.toString();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("traceId", context.traceId());
        metadata.put("routeLabel", "knowledge-qa");
        metadata.put("routeSource", context.routeSource().isBlank() ? "routed-chat" : context.routeSource());
        copyIfPresent(result, metadata, "retrievalModeRequested");
        copyIfPresent(result, metadata, "retrievalModeResolved");
        copyIfPresent(result, metadata, "fallbackReason");
        copyIfPresent(result, metadata, "localGraphUsed");
        copyIfPresent(result, metadata, "ragUsed");
        copyIfPresent(result, metadata, "sources");

        Map<String, Object> finishResult = new LinkedHashMap<>();
        finishResult.put("content", replyText);
        finishResult.put("traceId", context.traceId());
        finishResult.put("routeLabel", "knowledge-qa");
        finishResult.put("routeSource", context.routeSource().isBlank() ? "routed-chat" : context.routeSource());
        copyIfPresent(result, finishResult, "retrievalModeRequested");
        copyIfPresent(result, finishResult, "retrievalModeResolved");
        copyIfPresent(result, finishResult, "fallbackReason");
        copyIfPresent(result, finishResult, "localGraphUsed");
        copyIfPresent(result, finishResult, "ragUsed");

        Object images = result.get("images");
        if (images instanceof List<?> imageList && !imageList.isEmpty()) {
            metadata.put("images", images);
            chatStreamingSupport.sendImageEvents(context.sender(), imageList, context.taskId());
            chatStreamingSupport.updateAssistantPlaceholder(
                    context.assistantMessageId(),
                    chatStreamingSupport.buildRichContent(replyText, imageList),
                    metadata,
                    "rich",
                    "COMPLETED"
            );
            finishResult.put("images", imageList);
        } else {
            chatStreamingSupport.updateAssistantPlaceholder(
                    context.assistantMessageId(),
                    replyText,
                    metadata,
                    "text",
                    "COMPLETED"
            );
        }

        webChatService.autoTitleIfNeeded(context.sessionId(), context.content());
        context.sender().sendEvent(InterviewStreamEventType.FINISH.value(), Map.of(
                "action", "chat",
                "result", finishResult));
        context.sender().sendEvent(InterviewStreamEventType.DONE.value(), "[DONE]");
        chatStreamingSupport.completeTask(context.taskId(), context.sender());
        return true;
    }

    private boolean isKnowledgeQaResult(TaskResponse response) {
        if (response == null) {
            return false;
        }
        if (!response.success()) {
            return true;
        }
        Object data = response.data();
        if (data == null) {
            return true;
        }
        if (data instanceof Map<?, ?> map) {
            return map.containsKey("answer") && !map.containsKey("clarification");
        }
        return false;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }
}
