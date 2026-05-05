package io.github.imzmq.interview.interview.application;

import io.github.imzmq.interview.config.observability.ObservabilitySwitchProperties;
import io.github.imzmq.interview.knowledge.application.observability.RAGObservabilityService;
import io.github.imzmq.interview.knowledge.application.evaluation.RAGQualityEvaluationService;
import io.github.imzmq.interview.knowledge.application.evaluation.RetrievalEvaluationService;
import io.github.imzmq.interview.mapper.knowledge.RagFeedbackMapper;
import io.github.imzmq.interview.mapper.knowledge.RagMetricsSnapshotMapper;
import io.github.imzmq.interview.skill.runtime.SkillTelemetryRecorder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObservabilityApplicationServiceTest {

    @Test
    void shouldFilterRecentTraceSummariesByStatusRiskAndQuery() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        RAGObservabilityService ragObservabilityService = mock(RAGObservabilityService.class);
        when(ragObservabilityService.listRecentSummaries(anyInt())).thenReturn(List.of(
                summary("trace-success", "COMPLETED", false, List.of()),
                summary("trace-slow", "COMPLETED", true, List.of("slow_trace")),
                summary("trace-fallback", "COMPLETED", false, List.of("fallback_triggered")),
                summary("trace-empty", "COMPLETED", false, List.of("retrieval_empty")),
                summary("trace-running", "RUNNING", false, List.of())
        ));
        ObservabilityApplicationService service = new ObservabilityApplicationService(
                ragObservabilityService,
                mock(RetrievalEvaluationService.class),
                mock(RAGQualityEvaluationService.class),
                properties,
                new SkillTelemetryRecorder(),
                mock(RagFeedbackMapper.class),
                mock(RagMetricsSnapshotMapper.class)
        );

        assertEquals(List.of("trace-success", "trace-fallback", "trace-empty"),
                service.getRecentRagTraces(10, "SUCCESS", false, false, false, false, null)
                .stream().map(RAGObservabilityService.TraceSummary::traceId).toList());
        assertEquals(List.of("trace-slow"), service.getRecentRagTraces(10, "SLOW", false, false, false, false, null)
                .stream().map(RAGObservabilityService.TraceSummary::traceId).toList());
        assertEquals(List.of("trace-running"), service.getRecentRagTraces(10, "RUNNING", false, false, false, false, null)
                .stream().map(RAGObservabilityService.TraceSummary::traceId).toList());
        assertEquals(List.of("trace-fallback"), service.getRecentRagTraces(10, null, false, true, false, false, null)
                .stream().map(RAGObservabilityService.TraceSummary::traceId).toList());
        assertEquals(List.of("trace-empty"), service.getRecentRagTraces(10, null, false, false, true, false, null)
                .stream().map(RAGObservabilityService.TraceSummary::traceId).toList());
        assertEquals(List.of("trace-slow", "trace-fallback", "trace-empty"),
                service.getRecentRagTraces(10, null, true, false, false, false, null)
                        .stream().map(RAGObservabilityService.TraceSummary::traceId).toList());
        assertEquals(List.of("trace-fallback"), service.getRecentRagTraces(10, null, false, false, false, false, "fallback")
                .stream().map(RAGObservabilityService.TraceSummary::traceId).toList());
        verify(ragObservabilityService, atLeastOnce()).listRecentSummaries(1000);
    }

    @Test
    void shouldFilterRecentTraceSummariesByTimeWindow() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        RAGObservabilityService ragObservabilityService = mock(RAGObservabilityService.class);
        when(ragObservabilityService.listRecentSummaries(anyInt())).thenReturn(List.of(
                summary("trace-early", "COMPLETED", false, List.of(), Instant.parse("2026-04-13T14:00:00Z"), Instant.parse("2026-04-13T14:01:00Z")),
                summary("trace-mid", "COMPLETED", false, List.of(), Instant.parse("2026-04-13T14:05:00Z"), Instant.parse("2026-04-13T14:06:00Z")),
                summary("trace-late", "COMPLETED", false, List.of(), Instant.parse("2026-04-13T14:10:00Z"), Instant.parse("2026-04-13T14:11:00Z"))
        ));
        ObservabilityApplicationService service = new ObservabilityApplicationService(
                ragObservabilityService,
                mock(RetrievalEvaluationService.class),
                mock(RAGQualityEvaluationService.class),
                properties,
                new SkillTelemetryRecorder(),
                mock(RagFeedbackMapper.class),
                mock(RagMetricsSnapshotMapper.class)
        );

        assertEquals(
                List.of("trace-mid"),
                service.getRecentRagTraces(
                                10,
                                null,
                                false,
                                false,
                                false,
                                false,
                                null,
                                Instant.parse("2026-04-13T14:05:30Z"),
                                Instant.parse("2026-04-13T14:06:30Z")
                        )
                        .stream().map(RAGObservabilityService.TraceSummary::traceId).toList()
        );
    }

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
                mock(RagFeedbackMapper.class),
                mock(RagMetricsSnapshotMapper.class)
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
                mock(RagFeedbackMapper.class),
                mock(RagMetricsSnapshotMapper.class)
        );

        Map<String, Object> result = service.getRetrievalMetrics(100, 6, null);

        assertNull(result.get("top3Recall"));
        assertEquals(90L, result.get("retrievalLatencyP95Ms"));
        assertEquals(120L, result.get("retrievalLatencyP99Ms"));
        assertEquals("hours", ((Map<?, ?>) result.get("window")).get("mode"));
        assertEquals(6, ((Map<?, ?>) result.get("window")).get("hours"));
        assertEquals("检索评测已关闭，Top-3 召回率不可用", result.get("message"));
    }

    private static RAGObservabilityService.TraceSummary summary(String traceId,
                                                               String traceStatus,
                                                               boolean slowTrace,
                                                               List<String> riskTags) {
        return summary(traceId, traceStatus, slowTrace, riskTags, Instant.parse("2026-04-13T14:00:00Z"), Instant.parse("2026-04-13T14:00:01Z"));
    }

    private static RAGObservabilityService.TraceSummary summary(String traceId,
                                                               String traceStatus,
                                                               boolean slowTrace,
                                                               List<String> riskTags,
                                                               Instant startedAt,
                                                               Instant endedAt) {
        return new RAGObservabilityService.TraceSummary(
                traceId,
                traceStatus,
                100L,
                100L,
                30L,
                90L,
                2,
                2,
                riskTags.contains("fallback_triggered") ? 1 : 0,
                0,
                slowTrace,
                riskTags,
                riskTags.size(),
                4,
                1,
                startedAt,
                endedAt
        );
    }
}






