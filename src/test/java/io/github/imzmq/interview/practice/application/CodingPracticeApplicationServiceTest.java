package io.github.imzmq.interview.practice.application;

import io.github.imzmq.interview.practice.application.agent.CodingPracticeAgent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodingPracticeApplicationServiceTest {

    @Test
    void delegatesTheUnchangedActionPayloadToThePracticeAgent() {
        CodingPracticeAgent agent = mock(CodingPracticeAgent.class);
        CodingPracticeApplicationService service = new CodingPracticeApplicationService(agent);
        Map<String, Object> input = new LinkedHashMap<>(Map.of("action", "batch-quiz-submit"));
        Map<String, Object> result = Map.of("status", "evaluated", "score", 88);
        when(agent.execute(input)).thenReturn(result);

        assertThat(service.execute(input)).isSameAs(result);
        verify(agent).execute(input);
    }
}
