package io.github.imzmq.interview.interview.application.agent.handler;

import io.github.imzmq.interview.interview.application.agent.InterviewOrchestratorAgent;
import io.github.imzmq.interview.interview.application.contract.result.InterviewSessionStarted;
import io.github.imzmq.interview.interview.domain.InterviewSession;
import io.github.imzmq.interview.agent.application.contract.extension.TaskHandler;
import io.github.imzmq.interview.agent.application.contract.task.TaskRequest;
import io.github.imzmq.interview.agent.application.contract.task.TaskType;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class InterviewStartTaskHandler implements TaskHandler {

    private final InterviewOrchestratorAgent interviewOrchestratorAgent;

    public InterviewStartTaskHandler(InterviewOrchestratorAgent interviewOrchestratorAgent) {
        this.interviewOrchestratorAgent = interviewOrchestratorAgent;
    }

    @Override
    public TaskType taskType() {
        return TaskType.INTERVIEW_START;
    }

    @Override
    public String receiverName() {
        return "InterviewOrchestratorAgent";
    }

    @Override
    public Object handle(TaskRequest request) {
        boolean skipIntro = false;
        if (request.payload() != null && request.payload().containsKey("skipIntro")) {
            Object val = request.payload().get("skipIntro");
            if (val instanceof Boolean bool) {
                skipIntro = bool;
            } else if (val instanceof String text) {
                skipIntro = Boolean.parseBoolean(text);
            }
        }
        List<String> excludedTopics = InterviewTaskHandlerSupport.readTextList(request.payload(), "excludedTopics");
        InterviewSession session = interviewOrchestratorAgent.startSession(
                InterviewTaskHandlerSupport.readText(request.payload(), "userId"),
                InterviewTaskHandlerSupport.readText(request.payload(), "topic"),
                InterviewTaskHandlerSupport.readText(request.payload(), "resumePath"),
                InterviewTaskHandlerSupport.readInt(request.payload(), "totalQuestions"),
                skipIntro,
                excludedTopics
        );
        return new InterviewSessionStarted(
                session.getId(),
                session.getCurrentQuestion(),
                session.getTotalQuestions()
        );
    }
}


