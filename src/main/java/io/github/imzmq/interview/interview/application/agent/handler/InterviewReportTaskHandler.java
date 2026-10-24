package io.github.imzmq.interview.interview.application.agent.handler;

import io.github.imzmq.interview.interview.application.agent.InterviewOrchestratorAgent;
import io.github.imzmq.interview.agent.application.contract.extension.TaskHandler;
import io.github.imzmq.interview.agent.application.contract.task.TaskRequest;
import io.github.imzmq.interview.agent.application.contract.task.TaskType;
import org.springframework.stereotype.Component;

@Component
public class InterviewReportTaskHandler implements TaskHandler {

    private final InterviewOrchestratorAgent interviewOrchestratorAgent;

    public InterviewReportTaskHandler(InterviewOrchestratorAgent interviewOrchestratorAgent) {
        this.interviewOrchestratorAgent = interviewOrchestratorAgent;
    }

    @Override
    public TaskType taskType() {
        return TaskType.INTERVIEW_REPORT;
    }

    @Override
    public String receiverName() {
        return "InterviewOrchestratorAgent";
    }

    @Override
    public Object handle(TaskRequest request) {
        return interviewOrchestratorAgent.generateFinalReport(
                InterviewTaskHandlerSupport.readText(request.payload(), "sessionId"),
                InterviewTaskHandlerSupport.readText(request.payload(), "userId")
        );
    }
}


