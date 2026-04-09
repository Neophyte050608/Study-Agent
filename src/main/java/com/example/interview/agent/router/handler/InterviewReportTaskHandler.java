package com.example.interview.agent.router.handler;

import com.example.interview.agent.InterviewOrchestratorAgent;
import com.example.interview.agent.router.TaskHandler;
import com.example.interview.agent.router.TaskHandlerSupport;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskType;
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
