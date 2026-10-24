package io.github.imzmq.interview.interview.application.chat;

import io.github.imzmq.interview.agent.runtime.InterviewOrchestratorAgent;
import io.github.imzmq.interview.agent.task.TaskRequest;
import io.github.imzmq.interview.agent.task.TaskResponse;
import io.github.imzmq.interview.agent.task.TaskType;
import io.github.imzmq.interview.interview.domain.InterviewSession;
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
@Order(10)
public class ActiveInterviewChatScenarioHandler implements ChatScenarioHandler {

    private final ChatSessionOperations chatSessionOperations;
    private final ChatTaskOperations chatTaskOperations;
    private final InterviewOrchestratorAgent interviewOrchestratorAgent;
    private final ChatIntentPolicy chatIntentPolicy;
    private final ChatStreamingOperations chatStreamingOperations;

    public ActiveInterviewChatScenarioHandler(ChatSessionOperations chatSessionOperations,
                                              ChatTaskOperations chatTaskOperations,
                                              InterviewOrchestratorAgent interviewOrchestratorAgent,
                                              ChatStreamingOperations chatStreamingOperations,
                                              ChatIntentPolicy chatIntentPolicy) {
        this.chatSessionOperations = chatSessionOperations;
        this.chatTaskOperations = chatTaskOperations;
        this.interviewOrchestratorAgent = interviewOrchestratorAgent;
        this.chatStreamingOperations = chatStreamingOperations;
        this.chatIntentPolicy = chatIntentPolicy;
    }

    @Override
    public boolean handle(StreamingChatContext context) {
        if (context.routeResponse() != null) {
            return false;
        }
        String activeInterviewId = chatSessionOperations.findActiveInterviewSessionId(context.sessionId());
        if (activeInterviewId == null || activeInterviewId.isBlank() || chatIntentPolicy.looksLikeExplicitModeSwitch(context.content())) {
            return false;
        }
        InterviewSession interviewSession = interviewOrchestratorAgent.getSession(activeInterviewId);
        if (interviewSession == null || interviewSession.getHistory().size() >= interviewSession.getTotalQuestions()) {
            return false;
        }

        Map<String, Object> answerPayload = new HashMap<>();
        answerPayload.put("sessionId", activeInterviewId);
        answerPayload.put("userAnswer", context.content());
        TaskRequest answerRequest = new TaskRequest(TaskType.INTERVIEW_ANSWER, answerPayload, Map.of(
                "sessionId", context.sessionId(),
                "userId", context.userId(),
                "history", context.history(),
                "traceId", context.traceId()
        ));
        TaskResponse answerResponse = chatTaskOperations.dispatch(answerRequest);

        if (chatStreamingOperations.isCancelled(context.taskId())) {
            return true;
        }

        String replyText = chatTaskOperations.extractReplyText(answerResponse);
        context.emitter().emit(StreamEventType.PROGRESS.value(), Map.of(
                "stage", "GENERATING", "label", "正在生成评价", "status", "running", "percent", 70));
        chatStreamingOperations.sendChunkedText(context.emitter(), replyText, context.taskId());

        if (chatStreamingOperations.isCancelled(context.taskId())) {
            return true;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("traceId", context.traceId());
        metadata.put("interviewSessionId", activeInterviewId);
        metadata.put("routeLabel", "interview-answer");
        metadata.put("routeSource", "active-session");
        if (answerResponse.data() instanceof InterviewOrchestratorAgent.AnswerResult ar && ar.finished()) {
            metadata.put("type", "interview_finished");
        } else {
            metadata.put("type", "interview_answer");
        }
        chatStreamingOperations.updateAssistantPlaceholder(context.assistantMessageId(), replyText, metadata, "text", "COMPLETED");

        context.emitter().emit(StreamEventType.FINISH.value(), Map.of(
                "action", "chat",
                "result", Map.of(
                        "content", replyText,
                        "traceId", context.traceId(),
                        "routeLabel", "interview-answer",
                        "routeSource", "active-session"
                )));
        context.emitter().done();
        chatStreamingOperations.completeTask(context.taskId(), context.emitter());
        return true;
    }
}
