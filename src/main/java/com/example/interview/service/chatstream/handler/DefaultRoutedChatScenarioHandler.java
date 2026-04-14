package com.example.interview.service.chatstream.handler;

import com.example.interview.agent.InterviewOrchestratorAgent;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.core.InterviewSession;
import com.example.interview.service.WebChatService;
import com.example.interview.service.chatstream.ChatScenarioHandler;
import com.example.interview.service.chatstream.ChatStreamingSupport;
import com.example.interview.service.chatstream.StreamingChatContext;
import com.example.interview.stream.InterviewStreamEventType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(200)
public class DefaultRoutedChatScenarioHandler implements ChatScenarioHandler {

    private final WebChatService webChatService;
    private final ChatStreamingSupport chatStreamingSupport;

    public DefaultRoutedChatScenarioHandler(WebChatService webChatService,
                                            ChatStreamingSupport chatStreamingSupport) {
        this.webChatService = webChatService;
        this.chatStreamingSupport = chatStreamingSupport;
    }

    @Override
    public boolean handle(StreamingChatContext context) {
        TaskResponse response = context.routeResponse();
        if (response == null) {
            return false;
        }

        if (response.data() instanceof Map<?, ?> dataMap
                && "batch_quiz".equals(dataMap.get("status"))
                && dataMap.containsKey("quizPayload")) {
            context.emitter().emit(InterviewStreamEventType.PROGRESS.value(), Map.of(
                    "stage", "GENERATING", "label", "正在生成题目", "status", "running", "percent", 100));
            context.emitter().emit(InterviewStreamEventType.QUIZ.value(), dataMap.get("quizPayload"));

            String quizJson = chatStreamingSupport.toJson(
                    dataMap.get("quizPayload"),
                    "已为您生成批量选择题，请在答题卡中作答。"
            );
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("traceId", context.traceId());
            metadata.put("type", "coding_practice_batch");
            metadata.put("codingSessionId", String.valueOf(dataMap.get("sessionId")));
            metadata.put("routeLabel", "coding-batch-quiz");
            metadata.put("routeSource", context.routeSource().isBlank() ? "routed-chat" : context.routeSource());
            chatStreamingSupport.updateAssistantPlaceholder(context.assistantMessageId(), quizJson, metadata, "quiz", "COMPLETED");

            context.emitter().emit(InterviewStreamEventType.FINISH.value(), Map.of(
                    "action", "chat",
                    "result", Map.of(
                            "content", quizJson,
                            "traceId", context.traceId(),
                            "routeLabel", "coding-batch-quiz",
                            "routeSource", context.routeSource().isBlank() ? "routed-chat" : context.routeSource()
                    )));
            context.emitter().done();
            chatStreamingSupport.completeTask(context.taskId(), context.emitter());
            return true;
        }

        if (response.data() instanceof Map<?, ?> dataMap
                && "scenario_question_generated".equals(dataMap.get("status"))
                && dataMap.containsKey("scenarioPayload")) {
            context.emitter().emit(InterviewStreamEventType.PROGRESS.value(), Map.of(
                    "stage", "GENERATING", "label", "正在生成场景题", "status", "running", "percent", 100));

            String scenarioJson = chatStreamingSupport.toJson(
                    dataMap.get("scenarioPayload"),
                    "已为您生成场景题答题卡，请在卡片中作答。"
            );
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("traceId", context.traceId());
            metadata.put("type", "coding_practice_scenario");
            metadata.put("codingSessionId", String.valueOf(dataMap.get("sessionId")));
            metadata.put("routeLabel", "coding-scenario-card");
            metadata.put("routeSource", context.routeSource().isBlank() ? "routed-chat" : context.routeSource());
            if (dataMap.get("scenarioPayload") instanceof com.example.interview.agent.CodingPracticeAgent.ScenarioPayload payload) {
                metadata.put("cardId", payload.cardId());
            }
            chatStreamingSupport.updateAssistantPlaceholder(context.assistantMessageId(), scenarioJson, metadata, "scenario_card", "COMPLETED");

            context.emitter().emit(InterviewStreamEventType.FINISH.value(), Map.of(
                    "action", "chat",
                    "result", Map.of(
                            "content", "",
                            "scenarioPayload", dataMap.get("scenarioPayload"),
                            "assistantMessageId", context.assistantMessageId(),
                            "traceId", context.traceId(),
                            "routeLabel", "coding-scenario-card",
                            "routeSource", context.routeSource().isBlank() ? "routed-chat" : context.routeSource()
                    )));
            context.emitter().done();
            chatStreamingSupport.completeTask(context.taskId(), context.emitter());
            return true;
        }

        if (response.data() instanceof Map<?, ?> dataMap
                && "fill_question_generated".equals(dataMap.get("status"))
                && dataMap.containsKey("fillPayload")) {
            context.emitter().emit(InterviewStreamEventType.PROGRESS.value(), Map.of(
                    "stage", "GENERATING", "label", "正在生成填空题", "status", "running", "percent", 100));

            String fillJson = chatStreamingSupport.toJson(
                    dataMap.get("fillPayload"),
                    "已为您生成填空题答题卡，请在卡片中作答。"
            );
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("traceId", context.traceId());
            metadata.put("type", "coding_practice_fill");
            metadata.put("codingSessionId", String.valueOf(dataMap.get("sessionId")));
            metadata.put("routeLabel", "coding-fill-card");
            metadata.put("routeSource", context.routeSource().isBlank() ? "routed-chat" : context.routeSource());
            if (dataMap.get("fillPayload") instanceof com.example.interview.agent.CodingPracticeAgent.FillPayload payload) {
                metadata.put("cardId", payload.cardId());
            }
            chatStreamingSupport.updateAssistantPlaceholder(context.assistantMessageId(), fillJson, metadata, "fill_card", "COMPLETED");

            context.emitter().emit(InterviewStreamEventType.FINISH.value(), Map.of(
                    "action", "chat",
                    "result", Map.of(
                            "content", "",
                            "fillPayload", dataMap.get("fillPayload"),
                            "assistantMessageId", context.assistantMessageId(),
                            "traceId", context.traceId(),
                            "routeLabel", "coding-fill-card",
                            "routeSource", context.routeSource().isBlank() ? "routed-chat" : context.routeSource()
                    )));
            context.emitter().done();
            chatStreamingSupport.completeTask(context.taskId(), context.emitter());
            return true;
        }

        String replyText = webChatService.extractReplyText(response);
        context.emitter().emit(InterviewStreamEventType.PROGRESS.value(), Map.of(
                "stage", "GENERATING", "label", "正在生成回答", "status", "running", "percent", 70));
        chatStreamingSupport.sendChunkedText(context.emitter(), replyText, context.taskId());

        if (chatStreamingSupport.isCancelled(context.taskId())) {
            return true;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("traceId", context.traceId());
        String routeLabel = "task-router";
        if (webChatService.shouldClearSession(response)) {
            webChatService.clearSessionContext(context.sessionId(), context.assistantMessageId());
        }
        if (response.data() instanceof InterviewSession session) {
            metadata.put("interviewSessionId", session.getId());
            metadata.put("type", "interview_start");
            routeLabel = "interview-start";
        } else if (response.data() instanceof InterviewOrchestratorAgent.AnswerResult ar) {
            metadata.put("type", ar.finished() ? "interview_finished" : "interview_answer");
            routeLabel = ar.finished() ? "interview-finished" : "interview-answer";
        } else if (response.data() instanceof Map<?, ?> dataMap
                && "CodingPracticeAgent".equals(dataMap.get("agent"))) {
            Object codingSid = dataMap.get("sessionId");
            if (codingSid != null) {
                metadata.put("codingSessionId", String.valueOf(codingSid));
            }
            metadata.put("type", "coding_practice");
            routeLabel = "coding-practice";
        }
        metadata.put("routeLabel", routeLabel);
        metadata.put("routeSource", context.routeSource().isBlank() ? "routed-chat" : context.routeSource());
        chatStreamingSupport.updateAssistantPlaceholder(context.assistantMessageId(), replyText, metadata, "text", "COMPLETED");
        webChatService.autoTitleIfNeeded(context.sessionId(), context.content());

        context.emitter().emit(InterviewStreamEventType.FINISH.value(), Map.of(
                "action", "chat",
                "result", Map.of(
                        "content", replyText,
                        "traceId", context.traceId(),
                        "routeLabel", routeLabel,
                        "routeSource", context.routeSource().isBlank() ? "routed-chat" : context.routeSource()
                )));
        context.emitter().done();
        chatStreamingSupport.completeTask(context.taskId(), context.emitter());
        return true;
    }
}
