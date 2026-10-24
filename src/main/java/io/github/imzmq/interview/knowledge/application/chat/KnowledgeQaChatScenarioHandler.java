package io.github.imzmq.interview.knowledge.application.chat;

import io.github.imzmq.interview.knowledge.application.agent.KnowledgeQaAgent;
import io.github.imzmq.interview.agent.application.contract.task.TaskResponse;
import io.github.imzmq.interview.knowledge.domain.KnowledgeRetrievalMode;
import io.github.imzmq.interview.chat.application.contract.ChatSessionOperations;
import io.github.imzmq.interview.chat.application.contract.extension.ChatScenarioHandler;
import io.github.imzmq.interview.chat.application.contract.extension.ChatStreamingOperations;
import io.github.imzmq.interview.chat.application.contract.extension.StreamingChatContext;
import io.github.imzmq.interview.common.stream.StreamEventType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(100)
public class KnowledgeQaChatScenarioHandler implements ChatScenarioHandler {

    private final KnowledgeQaAgent knowledgeQaAgent;
    private final ChatSessionOperations chatSessionOperations;
    private final ChatStreamingOperations chatStreamingOperations;

    public KnowledgeQaChatScenarioHandler(KnowledgeQaAgent knowledgeQaAgent,
                                          ChatSessionOperations chatSessionOperations,
                                          ChatStreamingOperations chatStreamingOperations) {
        this.knowledgeQaAgent = knowledgeQaAgent;
        this.chatSessionOperations = chatSessionOperations;
        this.chatStreamingOperations = chatStreamingOperations;
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

        context.emitter().emit(StreamEventType.PROGRESS.value(), Map.of(
                "stage", "RETRIEVING", "label", "正在检索知识", "status", "running", "percent", 50));
        context.emitter().emit(StreamEventType.PROGRESS.value(), Map.of(
                "stage", "GENERATING", "label", "正在生成回答", "status", "running", "percent", 70));

        Map<String, Object> precomputedTurnAnalysis = extractPrecomputedTurnAnalysis(response);
        StringBuilder fullAnswer = new StringBuilder();
        Map<String, Object> result = chatStreamingOperations.streamObservedAnswer(
                context.emitter(),
                context.taskId(),
                tokenSink -> knowledgeQaAgent.executeStream(
                        context.content(),
                        context.history(),
                        KnowledgeRetrievalMode.fromNullable(context.retrievalMode(), null),
                        context.sessionId(),
                        token -> {
                            if (!chatStreamingOperations.isCancelled(context.taskId())) {
                                fullAnswer.append(token);
                                tokenSink.accept(token);
                            }
                        },
                        precomputedTurnAnalysis
                )
        );

        if (chatStreamingOperations.isCancelled(context.taskId())) {
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
            chatStreamingOperations.sendImageEvents(context.emitter(), imageList, context.taskId());
            chatStreamingOperations.updateAssistantPlaceholder(
                    context.assistantMessageId(),
                    chatStreamingOperations.buildRichContent(replyText, imageList),
                    metadata,
                    "rich",
                    "COMPLETED"
            );
            finishResult.put("images", imageList);
        } else {
            chatStreamingOperations.updateAssistantPlaceholder(
                    context.assistantMessageId(),
                    replyText,
                    metadata,
                    "text",
                    "COMPLETED"
            );
        }

        chatSessionOperations.autoTitleIfNeeded(context.sessionId(), context.content());
        context.emitter().emit(StreamEventType.FINISH.value(), Map.of(
                "action", "chat",
                "result", finishResult));
        context.emitter().done();
        chatStreamingOperations.completeTask(context.taskId(), context.emitter());
        return true;
    }

    private boolean isKnowledgeQaResult(TaskResponse response) {
        if (response == null) {
            return false;
        }
        if (!response.success()) {
            return false;
        }
        Object data = response.data();
        if (data == null) {
            return false;
        }
        if (data instanceof Map<?, ?> map) {
            return "KnowledgeQaAgent".equals(String.valueOf(map.get("agent")))
                    && Boolean.TRUE.equals(map.get("delegated"))
                    && "STREAM_EXECUTION".equals(String.valueOf(map.get("delegationType")))
                    && "KNOWLEDGE_QA".equals(String.valueOf(map.get("taskType")));
        }
        return false;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private Map<String, Object> extractPrecomputedTurnAnalysis(TaskResponse response) {
        if (response == null || !(response.data() instanceof Map<?, ?> data)) {
            return Map.of();
        }
        Map<String, Object> hints = new LinkedHashMap<>();
        copyFromUnknownMap(data, hints, "topicSwitch");
        copyFromUnknownMap(data, hints, "dialogAct");
        copyFromUnknownMap(data, hints, "infoNovelty");
        copyFromUnknownMap(data, hints, "currentTopic");
        copyFromUnknownMap(data, hints, "previousTopic");
        copyFromUnknownMap(data, hints, "contextPolicy");
        return hints;
    }

    private void copyFromUnknownMap(Map<?, ?> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }
}
