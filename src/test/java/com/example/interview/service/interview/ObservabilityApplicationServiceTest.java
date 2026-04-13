package com.example.interview.service.interview;

import com.example.interview.config.ObservabilitySwitchProperties;
import com.example.interview.service.RAGObservabilityService;
import com.example.interview.service.RAGQualityEvaluationService;
import com.example.interview.service.RetrievalEvaluationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                properties
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
                properties
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
