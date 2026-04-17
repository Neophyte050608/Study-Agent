package io.github.imzmq.interview.agent.router.handler;

import io.github.imzmq.interview.agent.runtime.InterviewOrchestratorAgent;
import io.github.imzmq.interview.agent.router.TaskHandler;
import io.github.imzmq.interview.agent.router.TaskHandlerSupport;
import io.github.imzmq.interview.agent.task.TaskRequest;
import io.github.imzmq.interview.agent.task.TaskType;
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
                TaskHandlerSupport.readText(request.payload(), "sessionId"),
                TaskHandlerSupport.readText(request.payload(), "userId")
        );
    }
}


