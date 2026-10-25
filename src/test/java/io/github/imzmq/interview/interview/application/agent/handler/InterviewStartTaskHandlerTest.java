package io.github.imzmq.interview.interview.application.agent.handler;

import io.github.imzmq.interview.agent.application.contract.task.TaskRequest;
import io.github.imzmq.interview.agent.application.contract.task.TaskType;
import io.github.imzmq.interview.interview.application.agent.InterviewOrchestratorAgent;
import io.github.imzmq.interview.interview.application.contract.result.InterviewSessionStarted;
import io.github.imzmq.interview.interview.domain.InterviewSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewStartTaskHandlerTest {

    @Test
    void maps_internal_session_to_public_start_result() {
        InterviewSession session = new InterviewSession();
        session.setId("interview-1");
        session.setCurrentQuestion("请介绍一次性能优化经历");
        session.setTotalQuestions(6);

        InterviewOrchestratorAgent orchestrator = new InterviewOrchestratorAgent(
                null, null, null, null, null, null, null, null
        ) {
            @Override
            public InterviewSession startSession(String userId, String topic, String resumePath,
                                                 Integer totalQuestions, boolean skipIntro,
                                                 List<String> excludedTopics) {
                return session;
            }
        };

        InterviewStartTaskHandler handler = new InterviewStartTaskHandler(orchestrator);

        Object result = handler.handle(new TaskRequest(
                TaskType.INTERVIEW_START,
                Map.of("topic", "JVM", "totalQuestions", 6),
                Map.of()
        ));

        assertThat(result).isEqualTo(new InterviewSessionStarted(
                "interview-1",
                "请介绍一次性能优化经历",
                6
        ));
    }
}
