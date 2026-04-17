package io.github.imzmq.interview.knowledge.application.chatstream.handler;

import io.github.imzmq.interview.agent.runtime.InterviewOrchestratorAgent;
import io.github.imzmq.interview.agent.task.TaskRequest;
import io.github.imzmq.interview.agent.task.TaskResponse;
import io.github.imzmq.interview.agent.task.TaskType;
import io.github.imzmq.interview.interview.domain.InterviewSession;
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
@Order(10)
public class ActiveInterviewChatScenarioHandler implements ChatScenarioHandler {

    private final WebChatService webChatService;
    private final InterviewOrchestratorAgent interviewOrchestratorAgent;
    private final ChatStreamingSupport chatStreamingSupport;

    public ActiveInterviewChatScenarioHandler(WebChatService webChatService,
                                              InterviewOrchestratorAgent interviewOrchestratorAgent,
                                              ChatStreamingSupport chatStreamingSupport) {
        this.webChatService = webChatService;
        this.interviewOrchestratorAgent = interviewOrchestratorAgent;
        this.chatStreamingSupport = chatStreamingSupport;
    }

    @Override
    public boolean handle(StreamingChatContext context) {
        if (context.routeResponse() != null) {
            return false;
        }
        String activeInterviewId = webChatService.findActiveInterviewSessionId(context.sessionId());
        if (activeInterviewId == null || activeInterviewId.isBlank() || ChatIntentHeuristics.looksLikeExplicitModeSwitch(context.content())) {
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
        TaskResponse answerResponse = webChatService.getTaskRouterAgent().dispatch(answerRequest);

        if (chatStreamingSupport.isCancelled(context.taskId())) {
            return true;
        }

        String replyText = webChatService.extractReplyText(answerResponse);
        context.emitter().emit(InterviewStreamEventType.PROGRESS.value(), Map.of(
                "stage", "GENERATING", "label", "正在生成评价", "status", "running", "percent", 70));
        chatStreamingSupport.sendChunkedText(context.emitter(), replyText, context.taskId());

        if (chatStreamingSupport.isCancelled(context.taskId())) {
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
        chatStreamingSupport.updateAssistantPlaceholder(context.assistantMessageId(), replyText, metadata, "text", "COMPLETED");

        context.emitter().emit(InterviewStreamEventType.FINISH.value(), Map.of(
                "action", "chat",
                "result", Map.of(
                        "content", replyText,
                        "traceId", context.traceId(),
                        "routeLabel", "interview-answer",
                        "routeSource", "active-session"
                )));
        context.emitter().done();
        chatStreamingSupport.completeTask(context.taskId(), context.emitter());
        return true;
    }
}










