package io.github.imzmq.interview.interview.application.chat;

import io.github.imzmq.interview.agent.task.TaskRequest;
import io.github.imzmq.interview.agent.task.TaskResponse;
import io.github.imzmq.interview.agent.task.TaskType;
import io.github.imzmq.interview.chat.application.contract.ChatSessionOperations;
import io.github.imzmq.interview.chat.application.contract.ChatTaskOperations;
import io.github.imzmq.interview.chat.application.contract.extension.ChatIntentPolicy;
import io.github.imzmq.interview.chat.application.contract.extension.ChatScenarioHandler;
import io.github.imzmq.interview.chat.application.contract.extension.ChatStreamingOperations;
import io.github.imzmq.interview.chat.application.contract.extension.StreamingChatContext;
import io.github.imzmq.interview.common.stream.StreamEventType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(20)
public class ActiveCodingChatScenarioHandler implements ChatScenarioHandler {

    private final ChatSessionOperations chatSessionOperations;
    private final ChatTaskOperations chatTaskOperations;
    private final ChatStreamingOperations chatStreamingOperations;
    private final ChatIntentPolicy chatIntentPolicy;

    public ActiveCodingChatScenarioHandler(ChatSessionOperations chatSessionOperations,
                                           ChatTaskOperations chatTaskOperations,
                                           ChatStreamingOperations chatStreamingOperations,
                                           ChatIntentPolicy chatIntentPolicy) {
        this.chatSessionOperations = chatSessionOperations;
        this.chatTaskOperations = chatTaskOperations;
        this.chatStreamingOperations = chatStreamingOperations;
        this.chatIntentPolicy = chatIntentPolicy;
    }

    @Override
    public boolean handle(StreamingChatContext context) {
        if (context.routeResponse() != null) {
            return false;
        }
        String activeCodingId = chatSessionOperations.findActiveCodingSessionId(context.sessionId());
        if (activeCodingId == null || activeCodingId.isBlank() || chatIntentPolicy.looksLikeExplicitModeSwitch(context.content())) {
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
        TaskResponse codingResponse = chatTaskOperations.dispatch(codingRequest);

        if (chatStreamingOperations.isCancelled(context.taskId())) {
            return true;
        }

        if (codingResponse.data() instanceof Map<?, ?> dataMap
                && "scenario_question_generated".equals(dataMap.get("status"))
                && dataMap.containsKey("scenarioPayload")) {
            context.emitter().emit(StreamEventType.PROGRESS.value(), Map.of(
                    "stage", "GENERATING", "label", "正在生成场景题", "status", "running", "percent", 100));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("traceId", context.traceId());
            metadata.put("type", "coding_practice_scenario");
            metadata.put("codingSessionId", String.valueOf(dataMap.get("sessionId")));
            metadata.put("routeLabel", "coding-scenario-card");
            metadata.put("routeSource", "active-session");
            if (dataMap.get("scenarioPayload") instanceof io.github.imzmq.interview.agent.runtime.CodingPracticeAgent.ScenarioPayload payload) {
                metadata.put("cardId", payload.cardId());
            }
            String scenarioJson = chatStreamingOperations.toJson(
                    dataMap.get("scenarioPayload"),
                    "已为您生成场景题答题卡，请在卡片中作答。"
            );
            chatStreamingOperations.updateAssistantPlaceholder(context.assistantMessageId(), scenarioJson, metadata, "scenario_card", "COMPLETED");
            context.emitter().emit(StreamEventType.FINISH.value(), Map.of(
                    "action", "chat",
                    "result", Map.of(
                            "content", "",
                            "scenarioPayload", dataMap.get("scenarioPayload"),
                            "assistantMessageId", context.assistantMessageId(),
                            "traceId", context.traceId(),
                            "routeLabel", "coding-scenario-card",
                            "routeSource", "active-session"
                    )));
            context.emitter().done();
            chatStreamingOperations.completeTask(context.taskId(), context.emitter());
            return true;
        }

        if (codingResponse.data() instanceof Map<?, ?> dataMap
                && "fill_question_generated".equals(dataMap.get("status"))
                && dataMap.containsKey("fillPayload")) {
            context.emitter().emit(StreamEventType.PROGRESS.value(), Map.of(
                    "stage", "GENERATING", "label", "正在生成填空题", "status", "running", "percent", 100));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("traceId", context.traceId());
            metadata.put("type", "coding_practice_fill");
            metadata.put("codingSessionId", String.valueOf(dataMap.get("sessionId")));
            metadata.put("routeLabel", "coding-fill-card");
            metadata.put("routeSource", "active-session");
            if (dataMap.get("fillPayload") instanceof io.github.imzmq.interview.agent.runtime.CodingPracticeAgent.FillPayload payload) {
                metadata.put("cardId", payload.cardId());
            }
            String fillJson = chatStreamingOperations.toJson(
                    dataMap.get("fillPayload"),
                    "已为您生成填空题答题卡，请在卡片中作答。"
            );
            chatStreamingOperations.updateAssistantPlaceholder(context.assistantMessageId(), fillJson, metadata, "fill_card", "COMPLETED");
            context.emitter().emit(StreamEventType.FINISH.value(), Map.of(
                    "action", "chat",
                    "result", Map.of(
                            "content", "",
                            "fillPayload", dataMap.get("fillPayload"),
                            "assistantMessageId", context.assistantMessageId(),
                            "traceId", context.traceId(),
                            "routeLabel", "coding-fill-card",
                            "routeSource", "active-session"
                    )));
            context.emitter().done();
            chatStreamingOperations.completeTask(context.taskId(), context.emitter());
            return true;
        }

        String replyText = chatTaskOperations.extractReplyText(codingResponse);
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
                TaskResponse nextResponse = chatTaskOperations.dispatch(nextRequest);
                if (nextResponse.success()) {
                    if (nextResponse.data() instanceof Map<?, ?> nextMap
                            && "batch_quiz".equals(nextMap.get("status"))
                            && nextMap.containsKey("quizPayload")) {
                        nextQuizPayload = nextMap.get("quizPayload");
                        nextQuizGenerated = true;
                    } else {
                        String nextText = chatTaskOperations.extractReplyText(nextResponse);
                        replyText = replyText + "\n\n---\n\n" + nextText;
                    }
                }
            }
        }

        context.emitter().emit(StreamEventType.PROGRESS.value(), Map.of(
                "stage", "GENERATING", "label", "正在评估答案", "status", "running", "percent", 70));
        chatStreamingOperations.sendChunkedText(context.emitter(), replyText, context.taskId());

        if (chatStreamingOperations.isCancelled(context.taskId())) {
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
            context.emitter().emit(StreamEventType.QUIZ.value(), nextQuizPayload);
            String quizJson = chatStreamingOperations.toJson(
                    nextQuizPayload,
                    "已为您生成批量选择题，请在答题卡中作答。"
            );
            chatStreamingOperations.updateAssistantPlaceholder(context.assistantMessageId(), quizJson, metadata, "quiz", "COMPLETED");
            finishResult.put("content", quizJson);
            finishResult.put("quizPayload", nextQuizPayload);
        } else {
            chatStreamingOperations.updateAssistantPlaceholder(context.assistantMessageId(), replyText, metadata, "text", "COMPLETED");
        }

        context.emitter().emit(StreamEventType.FINISH.value(), Map.of(
                "action", "chat",
                "result", finishResult));
        context.emitter().done();
        chatStreamingOperations.completeTask(context.taskId(), context.emitter());
        return true;
    }
}
