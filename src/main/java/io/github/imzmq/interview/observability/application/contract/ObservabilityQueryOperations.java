package io.github.imzmq.interview.observability.application.contract;

import io.github.imzmq.interview.observability.application.contract.query.RetrievalLatencyStats;
import io.github.imzmq.interview.observability.application.contract.query.TraceDetailView;
import io.github.imzmq.interview.observability.application.contract.query.TraceSummary;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Cross-module operational query surface owned by observability. */
public interface ObservabilityQueryOperations {

    List<TraceSummary> getRecentRagTraces(int limit);

    List<TraceSummary> getRecentRagTraces(
            int limit,
            String status,
            boolean riskyOnly,
            boolean fallbackOnly,
            boolean emptyRetrievalOnly,
            boolean slowOnly,
            String query
    );

    List<TraceSummary> getRecentRagTraces(
            int limit,
            String status,
            boolean riskyOnly,
            boolean fallbackOnly,
            boolean emptyRetrievalOnly,
            boolean slowOnly,
            String query,
            Instant startedAfter,
            Instant endedBefore
    );

    List<TraceSummary> getActiveRagTraces(int limit);

    TraceDetailView getRagTraceDetail(String traceId);

    RetrievalLatencyStats getRetrievalLatencyStats(Integer limit, Integer hours);

    Map<String, Object> getRagOverview();

    Map<String, Object> getDashboard();

    List<Map<String, Object>> getMetricsHistory(int hours, String metricsParam);

    Map<String, Object> getMetricsSummary();

    Map<String, Object> triggerSnapshot();
}
