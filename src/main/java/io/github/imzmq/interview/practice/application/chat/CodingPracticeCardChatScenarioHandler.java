package io.github.imzmq.interview.practice.application.chat;

import io.github.imzmq.interview.agent.application.contract.task.TaskResponse;
import io.github.imzmq.interview.chat.application.contract.ChatSessionOperations;
import io.github.imzmq.interview.chat.application.contract.ChatTaskOperations;
import io.github.imzmq.interview.chat.application.contract.extension.ChatScenarioHandler;
import io.github.imzmq.interview.chat.application.contract.extension.ChatStreamingOperations;
import io.github.imzmq.interview.chat.application.contract.extension.StreamingChatContext;
import io.github.imzmq.interview.common.stream.StreamEventType;
import io.github.imzmq.interview.practice.application.dto.FillPayload;
import io.github.imzmq.interview.practice.application.dto.ScenarioPayload;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(190)
public class CodingPracticeCardChatScenarioHandler implements ChatScenarioHandler {

    private final ChatSessionOperations chatSessionOperations;
    private final ChatTaskOperations chatTaskOperations;
    private final ChatStreamingOperations chatStreamingOperations;

    public CodingPracticeCardChatScenarioHandler(ChatSessionOperations chatSessionOperations,
                                                 ChatTaskOperations chatTaskOperations,
                                                 ChatStreamingOperations chatStreamingOperations) {
        this.chatSessionOperations = chatSessionOperations;
        this.chatTaskOperations = chatTaskOperations;
        this.chatStreamingOperations = chatStreamingOperations;
    }

    @Override
    public boolean handle(StreamingChatContext context) {
        TaskResponse response = context.routeResponse();
        if (response == null || !(response.data() instanceof Map<?, ?> dataMap)) {
            return false;
        }

        if ("batch_quiz".equals(dataMap.get("status")) && dataMap.containsKey("quizPayload")) {
            return handleBatchQuiz(context, dataMap);
        }
        if ("scenario_question_generated".equals(dataMap.get("status"))
                && dataMap.containsKey("scenarioPayload")) {
            return handleScenarioCard(context, dataMap);
        }
        if ("fill_question_generated".equals(dataMap.get("status"))
                && dataMap.containsKey("fillPayload")) {
            return handleFillCard(context, dataMap);
        }
        if ("CodingPracticeAgent".equals(dataMap.get("agent"))) {
            return handleCodingText(context, response, dataMap);
        }
        return false;
    }

    private boolean handleBatchQuiz(StreamingChatContext context, Map<?, ?> dataMap) {
        context.emitter().emit(StreamEventType.PROGRESS.value(), Map.of(
                "stage", "GENERATING", "label", "正在生成题目", "status", "running", "percent", 100));
        context.emitter().emit(StreamEventType.QUIZ.value(), dataMap.get("quizPayload"));

        String quizJson = chatStreamingOperations.toJson(
                dataMap.get("quizPayload"),
                "已为您生成批量选择题，请在答题卡中作答。"
        );
        Map<String, Object> metadata = codingMetadata(context, dataMap, "coding_practice_batch", "coding-batch-quiz");
        chatStreamingOperations.updateAssistantPlaceholder(
                context.assistantMessageId(), quizJson, metadata, "quiz", "COMPLETED");

        emitFinish(context, quizJson, "coding-batch-quiz");
        return true;
    }

    private boolean handleScenarioCard(StreamingChatContext context, Map<?, ?> dataMap) {
        context.emitter().emit(StreamEventType.PROGRESS.value(), Map.of(
                "stage", "GENERATING", "label", "正在生成场景题", "status", "running", "percent", 100));

        Object scenarioPayload = dataMap.get("scenarioPayload");
        String scenarioJson = chatStreamingOperations.toJson(
                scenarioPayload,
                "已为您生成场景题答题卡，请在卡片中作答。"
        );
        Map<String, Object> metadata = codingMetadata(
                context, dataMap, "coding_practice_scenario", "coding-scenario-card");
        if (scenarioPayload instanceof ScenarioPayload payload) {
            metadata.put("cardId", payload.cardId());
        }
        chatStreamingOperations.updateAssistantPlaceholder(
                context.assistantMessageId(), scenarioJson, metadata, "scenario_card", "COMPLETED");

        context.emitter().emit(StreamEventType.FINISH.value(), Map.of(
                "action", "chat",
                "result", Map.of(
                        "content", "",
                        "scenarioPayload", scenarioPayload,
                        "assistantMessageId", context.assistantMessageId(),
                        "traceId", context.traceId(),
                        "routeLabel", "coding-scenario-card",
                        "routeSource", routeSource(context)
                )));
        complete(context);
        return true;
    }

    private boolean handleFillCard(StreamingChatContext context, Map<?, ?> dataMap) {
        context.emitter().emit(StreamEventType.PROGRESS.value(), Map.of(
                "stage", "GENERATING", "label", "正在生成填空题", "status", "running", "percent", 100));

        Object fillPayload = dataMap.get("fillPayload");
        String fillJson = chatStreamingOperations.toJson(
                fillPayload,
                "已为您生成填空题答题卡，请在卡片中作答。"
        );
        Map<String, Object> metadata = codingMetadata(
                context, dataMap, "coding_practice_fill", "coding-fill-card");
        if (fillPayload instanceof FillPayload payload) {
            metadata.put("cardId", payload.cardId());
        }
        chatStreamingOperations.updateAssistantPlaceholder(
                context.assistantMessageId(), fillJson, metadata, "fill_card", "COMPLETED");

        context.emitter().emit(StreamEventType.FINISH.value(), Map.of(
                "action", "chat",
                "result", Map.of(
                        "content", "",
                        "fillPayload", fillPayload,
                        "assistantMessageId", context.assistantMessageId(),
                        "traceId", context.traceId(),
                        "routeLabel", "coding-fill-card",
                        "routeSource", routeSource(context)
                )));
        complete(context);
        return true;
    }

    private boolean handleCodingText(StreamingChatContext context,
                                     TaskResponse response,
                                     Map<?, ?> dataMap) {
        String replyText = chatTaskOperations.extractReplyText(response);
        context.emitter().emit(StreamEventType.PROGRESS.value(), Map.of(
                "stage", "GENERATING", "label", "正在生成回答", "status", "running", "percent", 70));
        chatStreamingOperations.sendChunkedText(context.emitter(), replyText, context.taskId());

        if (chatStreamingOperations.isCancelled(context.taskId())) {
            return true;
        }

        if (chatTaskOperations.shouldClearSession(response)) {
            chatSessionOperations.clearSessionContext(context.sessionId(), context.assistantMessageId());
        }
        Map<String, Object> metadata = codingMetadata(
                context, dataMap, "coding_practice", "coding-practice");
        chatStreamingOperations.updateAssistantPlaceholder(
                context.assistantMessageId(), replyText, metadata, "text", "COMPLETED");
        chatSessionOperations.autoTitleIfNeeded(context.sessionId(), context.content());
        emitFinish(context, replyText, "coding-practice");
        return true;
    }

    private Map<String, Object> codingMetadata(StreamingChatContext context,
                                               Map<?, ?> dataMap,
                                               String type,
                                               String routeLabel) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("traceId", context.traceId());
        metadata.put("type", type);
        Object codingSessionId = dataMap.get("sessionId");
        if (codingSessionId != null) {
            metadata.put("codingSessionId", String.valueOf(codingSessionId));
        }
        metadata.put("routeLabel", routeLabel);
        metadata.put("routeSource", routeSource(context));
        return metadata;
    }

    private void emitFinish(StreamingChatContext context, String content, String routeLabel) {
        context.emitter().emit(StreamEventType.FINISH.value(), Map.of(
                "action", "chat",
                "result", Map.of(
                        "content", content,
                        "traceId", context.traceId(),
                        "routeLabel", routeLabel,
                        "routeSource", routeSource(context)
                )));
        complete(context);
    }

    private void complete(StreamingChatContext context) {
        context.emitter().done();
        chatStreamingOperations.completeTask(context.taskId(), context.emitter());
    }

    private String routeSource(StreamingChatContext context) {
        return context.routeSource().isBlank() ? "routed-chat" : context.routeSource();
    }
}
