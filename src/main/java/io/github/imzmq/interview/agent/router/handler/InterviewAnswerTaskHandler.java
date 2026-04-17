package io.github.imzmq.interview.agent.router.handler;

import io.github.imzmq.interview.agent.runtime.InterviewOrchestratorAgent;
import io.github.imzmq.interview.agent.router.TaskHandler;
import io.github.imzmq.interview.agent.router.TaskHandlerSupport;
import io.github.imzmq.interview.agent.task.TaskRequest;
import io.github.imzmq.interview.agent.task.TaskType;
import org.springframework.stereotype.Component;

@Component
public class InterviewAnswerTaskHandler implements TaskHandler {

    private final InterviewOrchestratorAgent interviewOrchestratorAgent;

    public InterviewAnswerTaskHandler(InterviewOrchestratorAgent interviewOrchestratorAgent) {
        this.interviewOrchestratorAgent = interviewOrchestratorAgent;
    }

    @Override
    public TaskType taskType() {
        return TaskType.INTERVIEW_ANSWER;
    }

    @Override
    public String receiverName() {
        return "InterviewOrchestratorAgent";
    }

    @Override
    public Object handle(TaskRequest request) {
        return interviewOrchestratorAgent.submitAnswer(
                TaskHandlerSupport.readText(request.payload(), "sessionId"),
                TaskHandlerSupport.readText(request.payload(), "userAnswer")
        );
    }
}


