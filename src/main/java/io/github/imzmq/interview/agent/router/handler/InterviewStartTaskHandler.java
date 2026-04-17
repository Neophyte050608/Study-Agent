package io.github.imzmq.interview.agent.router.handler;

import io.github.imzmq.interview.agent.runtime.InterviewOrchestratorAgent;
import io.github.imzmq.interview.agent.router.TaskHandler;
import io.github.imzmq.interview.agent.router.TaskHandlerSupport;
import io.github.imzmq.interview.agent.task.TaskRequest;
import io.github.imzmq.interview.agent.task.TaskType;
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
        return interviewOrchestratorAgent.startSession(
                TaskHandlerSupport.readText(request.payload(), "userId"),
                TaskHandlerSupport.readText(request.payload(), "topic"),
                TaskHandlerSupport.readText(request.payload(), "resumePath"),
                TaskHandlerSupport.readInt(request.payload(), "totalQuestions"),
                skipIntro
        );
    }
}


