package io.github.imzmq.interview.knowledge.application.chatstream.handler;

import io.github.imzmq.interview.agent.task.TaskRequest;
import io.github.imzmq.interview.agent.task.TaskResponse;
import io.github.imzmq.interview.agent.task.TaskType;
import io.github.imzmq.interview.interview.application.WebChatService;
import io.github.imzmq.interview.knowledge.application.chatstream.ChatIntentHeuristics;
import io.github.imzmq.interview.knowledge.application.chatstream.ChatScenarioHandler;
import io.github.imzmq.interview.knowledge.application.chatstream.ChatStreamingSupport;
import io.github.imzmq.interview.knowledge.application.chatstream.StreamingChatContext;
import io.github.imzmq.interview.stream.runtime.InterviewStreamEventType;
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

        if (codingResponse.data() instanceof Map<?, ?> dataMap
                && "scenario_question_generated".equals(dataMap.get("status"))
                && dataMap.containsKey("scenarioPayload")) {
            context.emitter().emit(InterviewStreamEventType.PROGRESS.value(), Map.of(
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
            String scenarioJson = chatStreamingSupport.toJson(
                    dataMap.get("scenarioPayload"),
                    "已为您生成场景题答题卡，请在卡片中作答。"
            );
            chatStreamingSupport.updateAssistantPlaceholder(context.assistantMessageId(), scenarioJson, metadata, "scenario_card", "COMPLETED");
            context.emitter().emit(InterviewStreamEventType.FINISH.value(), Map.of(
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
            chatStreamingSupport.completeTask(context.taskId(), context.emitter());
            return true;
        }

        if (codingResponse.data() instanceof Map<?, ?> dataMap
                && "fill_question_generated".equals(dataMap.get("status"))
                && dataMap.containsKey("fillPayload")) {
            context.emitter().emit(InterviewStreamEventType.PROGRESS.value(), Map.of(
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
            String fillJson = chatStreamingSupport.toJson(
                    dataMap.get("fillPayload"),
                    "已为您生成填空题答题卡，请在卡片中作答。"
            );
            chatStreamingSupport.updateAssistantPlaceholder(context.assistantMessageId(), fillJson, metadata, "fill_card", "COMPLETED");
            context.emitter().emit(InterviewStreamEventType.FINISH.value(), Map.of(
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
            chatStreamingSupport.completeTask(context.taskId(), context.emitter());
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

        context.emitter().emit(InterviewStreamEventType.PROGRESS.value(), Map.of(
                "stage", "GENERATING", "label", "正在评估答案", "status", "running", "percent", 70));
        chatStreamingSupport.sendChunkedText(context.emitter(), replyText, context.taskId());

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
            context.emitter().emit(InterviewStreamEventType.QUIZ.value(), nextQuizPayload);
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

        context.emitter().emit(InterviewStreamEventType.FINISH.value(), Map.of(
                "action", "chat",
                "result", finishResult));
        context.emitter().done();
        chatStreamingSupport.completeTask(context.taskId(), context.emitter());
        return true;
    }
}







