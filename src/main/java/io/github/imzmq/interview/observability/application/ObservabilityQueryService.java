package io.github.imzmq.interview.observability.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.config.observability.ObservabilitySwitchProperties;
import io.github.imzmq.interview.feedback.application.contract.FeedbackMetricsQuery;
import io.github.imzmq.interview.feedback.application.contract.result.FeedbackMetrics;
import io.github.imzmq.interview.observability.application.contract.ObservabilityQueryOperations;
import io.github.imzmq.interview.observability.application.contract.query.RetrievalLatencyStats;
import io.github.imzmq.interview.observability.application.contract.query.TraceDetailView;
import io.github.imzmq.interview.observability.application.contract.query.TraceNodeView;
import io.github.imzmq.interview.observability.application.contract.query.TraceSummary;
import io.github.imzmq.interview.observability.application.contract.query.TraceView;
import io.github.imzmq.interview.observability.application.metrics.MetricsSnapshotScheduler;
import io.github.imzmq.interview.observability.application.trace.RAGObservabilityService;
import io.github.imzmq.interview.observability.infrastructure.persistence.RagMetricsSnapshotDO;
import io.github.imzmq.interview.observability.infrastructure.persistence.RagMetricsSnapshotMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ObservabilityQueryService implements ObservabilityQueryOperations {
    private static final int TRACE_FILTER_SCAN_LIMIT = 1_000;

    private final RAGObservabilityService ragObservabilityService;
    private final ObservabilitySwitchProperties observabilitySwitchProperties;
    private final FeedbackMetricsQuery feedbackMetricsQuery;
    private final RagMetricsSnapshotMapper ragMetricsSnapshotMapper;
    private final MetricsSnapshotScheduler metricsSnapshotScheduler;

    public ObservabilityQueryService(RAGObservabilityService ragObservabilityService,
                                     ObservabilitySwitchProperties observabilitySwitchProperties,
                                     FeedbackMetricsQuery feedbackMetricsQuery,
                                     RagMetricsSnapshotMapper ragMetricsSnapshotMapper,
                                     MetricsSnapshotScheduler metricsSnapshotScheduler) {
        this.ragObservabilityService = ragObservabilityService;
        this.observabilitySwitchProperties = observabilitySwitchProperties;
        this.feedbackMetricsQuery = feedbackMetricsQuery;
        this.ragMetricsSnapshotMapper = ragMetricsSnapshotMapper;
        this.metricsSnapshotScheduler = metricsSnapshotScheduler;
    }

    public List<TraceSummary> getRecentRagTraces(int limit) {
        return getRecentRagTraces(limit, null, false, false, false, false, null, null, null);
    }

    public List<TraceSummary> getRecentRagTraces(int limit,
                                                                         String status,
                                                                         boolean riskyOnly,
                                                                         boolean fallbackOnly,
                                                                         boolean emptyRetrievalOnly,
                                                                         boolean slowOnly,
                                                                         String query) {
        return getRecentRagTraces(limit, status, riskyOnly, fallbackOnly, emptyRetrievalOnly, slowOnly, query, null, null);
    }

    public List<TraceSummary> getRecentRagTraces(int limit,
                                                                         String status,
                                                                         boolean riskyOnly,
                                                                         boolean fallbackOnly,
                                                                         boolean emptyRetrievalOnly,
                                                                         boolean slowOnly,
                                                                         String query,
                                                                         Instant startedAfter,
                                                                         Instant endedBefore) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return List.of();
        }
        int effectiveLimit = limit <= 0 ? 20 : limit;
        int scanLimit = Math.max(effectiveLimit, TRACE_FILTER_SCAN_LIMIT);
        return ragObservabilityService.listRecentSummaries(scanLimit).stream()
                .map(this::toTraceSummary)
                .filter(item -> matchesStatus(item, status))
                .filter(item -> !riskyOnly || item.riskCount() != null && item.riskCount() > 0)
                .filter(item -> !fallbackOnly || containsRisk(item, "fallback_triggered"))
                .filter(item -> !emptyRetrievalOnly || containsRisk(item, "retrieval_empty"))
                .filter(item -> !slowOnly || containsRisk(item, "slow_trace") || containsRisk(item, "slow_first_token"))
                .filter(item -> matchesQuery(item, query))
                .filter(item -> matchesTimeWindow(item, startedAfter, endedBefore))
                .limit(effectiveLimit)
                .toList();
    }

    public List<TraceSummary> getActiveRagTraces(int limit) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return List.of();
        }
        return ragObservabilityService.listActiveSummaries(limit).stream()
                .map(this::toTraceSummary)
                .toList();
    }

    public TraceDetailView getRagTraceDetail(String traceId) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return null;
        }
        return toTraceDetailView(ragObservabilityService.getTraceDetailView(traceId));
    }

    @Override
    public RetrievalLatencyStats getRetrievalLatencyStats(Integer limit, Integer hours) {
        RAGObservabilityService.RetrievalLatencyStats stats =
                ragObservabilityService.getRetrievalLatencyStats(limit, hours);
        return new RetrievalLatencyStats(
                stats.p95LatencyMs(),
                stats.p99LatencyMs(),
                stats.sampleSize(),
                stats.windowMode(),
                stats.windowLimit(),
                stats.windowHours()
        );
    }

    private TraceSummary toTraceSummary(RAGObservabilityService.TraceSummary summary) {
        return new TraceSummary(
                summary.traceId(),
                summary.traceStatus(),
                summary.businessDurationMs(),
                summary.connectionDurationMs(),
                summary.firstTokenMs(),
                summary.streamDispatchMs(),
                summary.retrievedDocCount(),
                summary.displayedDocCount(),
                summary.fallbackCount(),
                summary.failedNodeCount(),
                summary.slowTrace(),
                summary.riskTags(),
                summary.riskCount(),
                summary.nodeCount(),
                summary.retrievalNodeCount(),
                summary.startedAt(),
                summary.endedAt(),
                summary.followUpCount()
        );
    }

    private TraceDetailView toTraceDetailView(RAGObservabilityService.TraceDetailView detail) {
        if (detail == null) {
            return null;
        }
        RAGObservabilityService.RAGTrace trace = detail.trace();
        List<TraceNodeView> nodes = trace.nodes().stream()
                .map(node -> new TraceNodeView(
                        node.nodeId(),
                        node.parentNodeId(),
                        node.nodeType(),
                        node.nodeName(),
                        node.startTime(),
                        node.endTime(),
                        node.inputSummary(),
                        node.outputSummary(),
                        node.errorSummary(),
                        node.metrics(),
                        node.details(),
                        node.status(),
                        node.durationMs()
                ))
                .toList();
        TraceView traceView = new TraceView(
                trace.traceId(),
                trace.startTime(),
                nodes,
                trace.getDurationMs(),
                trace.getEffectiveStartTime(),
                trace.getEffectiveEndTime()
        );
        return new TraceDetailView(traceView, toTraceSummary(detail.summary()));
    }

    public Map<String, Object> getRagOverview() {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return Map.of("enabled", false, "avgLatencyMs", 0, "avgRetrievedDocs", 0.0, "cacheHitRate", "0.0%");
        }
        return ragObservabilityService.getOverview();
    }

    public Map<String, Object> getDashboard() {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return Map.of("enabled", false, "alertLevel", "NONE", "alertTags", List.of());
        }

        Map<String, Object> overview = ragObservabilityService.getOverview();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime hourStart = now.truncatedTo(ChronoUnit.HOURS);

        FeedbackMetrics feedbackMetrics = feedbackMetricsQuery.summarize(hourStart, now);
        long thumbsUp = feedbackMetrics.thumbsUpCount();
        long thumbsDown = feedbackMetrics.thumbsDownCount();
        long copyCount = feedbackMetrics.copyCount();
        long feedbackTotal = thumbsUp + thumbsDown;
        double satisfactionRate = feedbackTotal > 0 ? (double) thumbsUp / feedbackTotal : 0.0;

        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("thumbsUp", thumbsUp);
        feedback.put("thumbsDown", thumbsDown);
        feedback.put("copy", copyCount);
        feedback.put("satisfactionRate", String.format(Locale.ROOT, "%.1f%%", satisfactionRate * 100));

        Map<String, Object> currentHour = new LinkedHashMap<>(overview);
        currentHour.put("feedback", feedback);

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("enabled", true);
        dashboard.put("currentHour", currentHour);
        dashboard.put("alertLevel", overview.get("alertLevel"));
        dashboard.put("alertTags", overview.get("alertTags"));
        dashboard.put("trendBuckets", overview.get("trendBuckets"));
        dashboard.put("riskTagCounts", overview.get("riskTagCounts"));
        return dashboard;
    }

    public List<Map<String, Object>> getMetricsHistory(int hours, String metricsParam) {
        if (!observabilitySwitchProperties.isRagTraceEnabled() || hours <= 0) {
            return List.of();
        }
        int safeHours = Math.min(hours, 720);
        LocalDateTime cutoff = LocalDateTime.now().minusHours(safeHours);
        List<RagMetricsSnapshotDO> snapshots = ragMetricsSnapshotMapper.selectList(
                new LambdaQueryWrapper<RagMetricsSnapshotDO>()
                        .ge(RagMetricsSnapshotDO::getSnapshotHour, cutoff)
                        .orderByAsc(RagMetricsSnapshotDO::getSnapshotHour));

        Set<String> requestedMetrics = metricsParam == null || metricsParam.isBlank()
                ? Set.of("avgLatencyMs", "p95LatencyMs", "satisfactionRate")
                : Set.of(metricsParam.split(","));

        List<Map<String, Object>> result = new ArrayList<>();
        for (RagMetricsSnapshotDO snap : snapshots) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("hour", snap.getSnapshotHour().toString());
            if (requestedMetrics.contains("avgLatencyMs"))
                point.put("avgLatencyMs", snap.getAvgDurationMs());
            if (requestedMetrics.contains("p95LatencyMs"))
                point.put("p95LatencyMs", snap.getP95DurationMs());
            if (requestedMetrics.contains("satisfactionRate"))
                point.put("satisfactionRate", String.format(Locale.ROOT, "%.1f%%", snap.getSatisfactionRate() * 100));
            if (requestedMetrics.contains("fallbackRate")) {
                double fbRate = snap.getTraceCount() > 0
                        ? (double) snap.getFallbackCount() / snap.getTraceCount() * 100 : 0;
                point.put("fallbackRate", String.format(Locale.ROOT, "%.1f%%", fbRate));
            }
            if (requestedMetrics.contains("emptyRetrievalRate")) {
                double erRate = snap.getTraceCount() > 0
                        ? (double) snap.getEmptyRetrievalCount() / snap.getTraceCount() * 100 : 0;
                point.put("emptyRetrievalRate", String.format(Locale.ROOT, "%.1f%%", erRate));
            }
            if (requestedMetrics.contains("successRate")) {
                double sRate = snap.getTraceCount() > 0
                        ? (double) snap.getSuccessCount() / snap.getTraceCount() * 100 : 0;
                point.put("successRate", String.format(Locale.ROOT, "%.1f%%", sRate));
            }
            if (requestedMetrics.contains("traceCount"))
                point.put("traceCount", snap.getTraceCount());
            result.add(point);
        }
        return result;
    }

    public Map<String, Object> getMetricsSummary() {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return Map.of("enabled", false);
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.truncatedTo(ChronoUnit.DAYS);
        LocalDateTime yesterdayStart = todayStart.minusDays(1);
        LocalDateTime yesterdayEnd = todayStart;
        LocalDateTime thisWeekStart = todayStart.minusDays(now.getDayOfWeek().getValue() % 7);
        LocalDateTime lastWeekStart = thisWeekStart.minusDays(7);
        LocalDateTime lastWeekEnd = thisWeekStart;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("today", buildPeriodSummary(todayStart, now));
        summary.put("yesterday", buildPeriodSummary(yesterdayStart, yesterdayEnd));
        summary.put("thisWeek", buildPeriodSummary(thisWeekStart, now));
        summary.put("lastWeek", buildPeriodSummary(lastWeekStart, lastWeekEnd));
        return summary;
    }

    public Map<String, Object> triggerSnapshot() {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return Map.of("message", "RAG Trace 已关闭，无法生成快照");
        }
        return metricsSnapshotScheduler.backfillMissingSnapshots(168);
    }

    private boolean matchesStatus(TraceSummary item, String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return true;
        }
        String requested = status.trim().toUpperCase(Locale.ROOT);
        String traceStatus = item.traceStatus() == null ? "" : item.traceStatus().trim().toUpperCase(Locale.ROOT);
        String displayStatus = "COMPLETED".equals(traceStatus)
                ? (Boolean.TRUE.equals(item.slowTrace()) ? "SLOW" : "SUCCESS")
                : traceStatus;
        return requested.equals(displayStatus);
    }

    private boolean containsRisk(TraceSummary item, String riskTag) {
        return item.riskTags() != null && item.riskTags().contains(riskTag);
    }

    private boolean matchesQuery(TraceSummary item, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        String haystack = (item.traceId() == null ? "" : item.traceId()) + " "
                + String.join(" ", item.riskTags() == null ? List.of() : item.riskTags());
        return haystack.toLowerCase(Locale.ROOT).contains(normalized);
    }

    private boolean matchesTimeWindow(TraceSummary item, Instant startedAfter, Instant endedBefore) {
        Instant traceTime = item.endedAt() != null ? item.endedAt() : item.startedAt();
        if (traceTime == null) {
            return startedAfter == null && endedBefore == null;
        }
        if (startedAfter != null && traceTime.isBefore(startedAfter)) {
            return false;
        }
        if (endedBefore != null && traceTime.isAfter(endedBefore)) {
            return false;
        }
        return true;
    }

    private Map<String, Object> buildPeriodSummary(LocalDateTime from, LocalDateTime to) {
        List<RagMetricsSnapshotDO> snapshots = ragMetricsSnapshotMapper.selectList(
                new LambdaQueryWrapper<RagMetricsSnapshotDO>()
                        .ge(RagMetricsSnapshotDO::getSnapshotHour, from)
                        .lt(RagMetricsSnapshotDO::getSnapshotHour, to));

        int totalTraces = snapshots.stream().mapToInt(RagMetricsSnapshotDO::getTraceCount).sum();
        long totalDuration = snapshots.stream().mapToLong(s -> s.getAvgDurationMs() * s.getTraceCount()).sum();
        long avgLatency = totalTraces > 0 ? totalDuration / totalTraces : 0;
        long totalThumbsUp = snapshots.stream().mapToLong(RagMetricsSnapshotDO::getThumbsUpCount).sum();
        long totalThumbsDown = snapshots.stream().mapToLong(RagMetricsSnapshotDO::getThumbsDownCount).sum();
        long totalFeedback = totalThumbsUp + totalThumbsDown;
        double satRate = totalFeedback > 0 ? (double) totalThumbsUp / totalFeedback * 100 : 0;

        Map<String, Object> period = new LinkedHashMap<>();
        period.put("traceCount", totalTraces);
        period.put("avgLatencyMs", avgLatency);
        period.put("satisfactionRate", String.format(Locale.ROOT, "%.1f%%", satRate));
        return period;
    }
}
