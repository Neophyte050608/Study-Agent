package io.github.imzmq.interview.practice.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.chat.application.contract.ChatMessageOperations;
import io.github.imzmq.interview.practice.application.CodingPracticeApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodingPracticeControllerContractTest {

    @Test
    void preservesBasePathAndPostEndpoints() {
        RequestMapping requestMapping = CodingPracticeController.class.getAnnotation(RequestMapping.class);
        assertThat(requestMapping.value()).containsExactly("/api/coding");

        Set<String> postMappings = Arrays.stream(CodingPracticeController.class.getDeclaredMethods())
                .map(Method::getDeclaredAnnotations)
                .flatMap(Arrays::stream)
                .filter(PostMapping.class::isInstance)
                .map(PostMapping.class::cast)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .collect(Collectors.toSet());

        assertThat(postMappings).containsExactlyInAnyOrder(
                "/batch-quiz/submit",
                "/scenario-card/submit",
                "/fill-card/submit",
                "/scenario-card/next",
                "/fill-card/next"
        );
    }

    @Test
    void batchQuizSubmitPreservesActionAndAgentResult() {
        CodingPracticeApplicationService service = mock(CodingPracticeApplicationService.class);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", "coding-1");
        Map<String, Object> result = Map.of("status", "evaluated", "score", 88);
        when(service.execute(body)).thenReturn(result);
        CodingPracticeController controller = new CodingPracticeController(
                service,
                mock(ChatMessageOperations.class),
                new ObjectMapper()
        );

        var response = controller.submitBatchQuiz(body);

        assertThat(response.getBody()).isSameAs(result);
        assertThat(body).containsEntry("action", "batch-quiz-submit");
        verify(service).execute(body);
    }
}
