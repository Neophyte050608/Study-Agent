package io.github.imzmq.interview.interview.application.chat;

import io.github.imzmq.interview.interview.application.contract.result.InterviewAnswerResult;

import io.github.imzmq.interview.interview.application.agent.InterviewOrchestratorAgent;
import io.github.imzmq.interview.agent.application.contract.task.TaskResponse;
import io.github.imzmq.interview.interview.domain.InterviewSession;
import io.github.imzmq.interview.chat.application.contract.ChatSessionOperations;
import io.github.imzmq.interview.chat.application.contract.ChatTaskOperations;
import io.github.imzmq.interview.chat.application.contract.extension.ChatScenarioHandler;
import io.github.imzmq.interview.chat.application.contract.extension.ChatStreamingOperations;
import io.github.imzmq.interview.chat.application.contract.extension.StreamingChatContext;
import io.github.imzmq.interview.common.stream.StreamEventType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(200)
public class DefaultRoutedChatScenarioHandler implements ChatScenarioHandler {

    private final ChatSessionOperations chatSessionOperations;
    private final ChatTaskOperations chatTaskOperations;
    private final ChatStreamingOperations chatStreamingOperations;

    public DefaultRoutedChatScenarioHandler(ChatSessionOperations chatSessionOperations,
                                            ChatTaskOperations chatTaskOperations,
                                            ChatStreamingOperations chatStreamingOperations) {
        this.chatSessionOperations = chatSessionOperations;
        this.chatTaskOperations = chatTaskOperations;
        this.chatStreamingOperations = chatStreamingOperations;
    }

    @Override
    public boolean handle(StreamingChatContext context) {
        TaskResponse response = context.routeResponse();
        if (response == null) {
            return false;
        }

        if (response.data() instanceof InterviewSession session) {
            context.emitter().emit(StreamEventType.PROGRESS.value(), Map.of(
                    "stage", "GENERATING", "label", "正在生成面试题目", "status", "running", "percent", 100));

            String questionText = session.getCurrentQuestion();
            if (questionText == null || questionText.isBlank()) {
                questionText = chatTaskOperations.extractReplyText(response);
            }

            Map<String, Object> interviewPayload = new LinkedHashMap<>();
            interviewPayload.put("interviewState", "asking");
            interviewPayload.put("interviewSessionId", session.getId());
            interviewPayload.put("question", questionText);
            interviewPayload.put("questionIndex", 1);
            interviewPayload.put("totalQuestions", session.getTotalQuestions());
            interviewPayload.put("score", 0);

            String interviewJson = chatStreamingOperations.toJson(interviewPayload, questionText);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("traceId", context.traceId());
            metadata.put("interviewSessionId", session.getId());
            metadata.put("type", "interview_start");
            metadata.put("routeLabel", "interview-start");
            metadata.put("routeSource", context.routeSource().isBlank() ? "routed-chat" : context.routeSource());
            chatStreamingOperations.updateAssistantPlaceholder(context.assistantMessageId(), interviewJson, metadata, "interview_card", "COMPLETED");

            context.emitter().emit(StreamEventType.FINISH.value(), Map.of(
                    "action", "chat",
                    "result", Map.of(
                            "content", "",
                            "interviewPayload", interviewPayload,
                            "assistantMessageId", context.assistantMessageId(),
                            "traceId", context.traceId(),
                            "routeLabel", "interview-start",
                            "routeSource", context.routeSource().isBlank() ? "routed-chat" : context.routeSource()
                    )));
            context.emitter().done();
            chatStreamingOperations.completeTask(context.taskId(), context.emitter());
            return true;
        }

        String replyText = chatTaskOperations.extractReplyText(response);
        context.emitter().emit(StreamEventType.PROGRESS.value(), Map.of(
                "stage", "GENERATING", "label", "正在生成回答", "status", "running", "percent", 70));
        chatStreamingOperations.sendChunkedText(context.emitter(), replyText, context.taskId());

        if (chatStreamingOperations.isCancelled(context.taskId())) {
            return true;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("traceId", context.traceId());
        String routeLabel = "task-router";
        if (chatTaskOperations.shouldClearSession(response)) {
            chatSessionOperations.clearSessionContext(context.sessionId(), context.assistantMessageId());
        }
        if (response.data() instanceof InterviewSession session) {
            metadata.put("interviewSessionId", session.getId());
            metadata.put("type", "interview_start");
            routeLabel = "interview-start";
        } else if (response.data() instanceof InterviewAnswerResult ar) {
            metadata.put("type", ar.finished() ? "interview_finished" : "interview_answer");
            routeLabel = ar.finished() ? "interview-finished" : "interview-answer";
        }
        metadata.put("routeLabel", routeLabel);
        metadata.put("routeSource", context.routeSource().isBlank() ? "routed-chat" : context.routeSource());
        chatStreamingOperations.updateAssistantPlaceholder(context.assistantMessageId(), replyText, metadata, "text", "COMPLETED");
        chatSessionOperations.autoTitleIfNeeded(context.sessionId(), context.content());

        context.emitter().emit(StreamEventType.FINISH.value(), Map.of(
                "action", "chat",
                "result", Map.of(
                        "content", replyText,
                        "traceId", context.traceId(),
                        "routeLabel", routeLabel,
                        "routeSource", context.routeSource().isBlank() ? "routed-chat" : context.routeSource()
                )));
        context.emitter().done();
        chatStreamingOperations.completeTask(context.taskId(), context.emitter());
        return true;
    }
}
