package io.github.imzmq.interview.interview.application;

import io.github.imzmq.interview.config.observability.ObservabilitySwitchProperties;
import io.github.imzmq.interview.observability.application.ObservabilityQueryService;
import io.github.imzmq.interview.observability.application.trace.RAGObservabilityService;
import io.github.imzmq.interview.knowledge.application.evaluation.RAGQualityEvaluationService;
import io.github.imzmq.interview.knowledge.application.evaluation.RetrievalEvaluationService;
import io.github.imzmq.interview.skill.runtime.SkillTelemetryRecorder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObservabilityApplicationServiceTest {

    @Test
    void shouldAggregateRetrievalMetricsFromLatestEvalAndLatencyStats() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        properties.setRetrievalEvalEnabled(true);

        RAGObservabilityService ragObservabilityService = mock(RAGObservabilityService.class);
        when(ragObservabilityService.getRetrievalLatencyStats(200, null))
                .thenReturn(new RAGObservabilityService.RetrievalLatencyStats(180L, 260L, 36, "limit", 200, null));

        RetrievalEvaluationService retrievalEvaluationService = mock(RetrievalEvaluationService.class);
        when(retrievalEvaluationService.listRecentRuns(anyInt())).thenReturn(List.of(
                new RetrievalEvaluationService.RetrievalEvalRunSummary(
                        "run-latest",
                        "2026-04-15T00:00:00Z",
                        "default",
                        "latest-run",
                        "exp-1",
                        Map.of(),
                        "",
                        50,
                        45,
                        0.80D,
                        0.86D,
                        0.90D,
                        0.85D
                )
        ));

        ObservabilityApplicationService service = new ObservabilityApplicationService(
                ragObservabilityService,
                retrievalEvaluationService,
                mock(RAGQualityEvaluationService.class),
                properties,
                new SkillTelemetryRecorder(),
                mock(ObservabilityQueryService.class)
        );

        Map<String, Object> result = service.getRetrievalMetrics(200, null, null);

        assertEquals(0.86D, result.get("top3Recall"));
        assertEquals(180L, result.get("retrievalLatencyP95Ms"));
        assertEquals(260L, result.get("retrievalLatencyP99Ms"));
        assertEquals(36, result.get("retrievalLatencySampleSize"));
        assertEquals("run-latest", result.get("latestEvalRunId"));
        assertEquals("ok", result.get("message"));
        assertNotNull(result.get("window"));
    }

    @Test
    void shouldReturnMessageWhenRetrievalEvalDisabled() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        properties.setRetrievalEvalEnabled(false);

        RAGObservabilityService ragObservabilityService = mock(RAGObservabilityService.class);
        when(ragObservabilityService.getRetrievalLatencyStats(100, 6))
                .thenReturn(new RAGObservabilityService.RetrievalLatencyStats(90L, 120L, 12, "hours", null, 6));

        ObservabilityApplicationService service = new ObservabilityApplicationService(
                ragObservabilityService,
                mock(RetrievalEvaluationService.class),
                mock(RAGQualityEvaluationService.class),
                properties,
                new SkillTelemetryRecorder(),
                mock(ObservabilityQueryService.class)
        );

        Map<String, Object> result = service.getRetrievalMetrics(100, 6, null);

        assertNull(result.get("top3Recall"));
        assertEquals(90L, result.get("retrievalLatencyP95Ms"));
        assertEquals(120L, result.get("retrievalLatencyP99Ms"));
        assertEquals("hours", ((Map<?, ?>) result.get("window")).get("mode"));
        assertEquals(6, ((Map<?, ?>) result.get("window")).get("hours"));
        assertEquals("检索评测已关闭，Top-3 召回率不可用", result.get("message"));
    }

}
