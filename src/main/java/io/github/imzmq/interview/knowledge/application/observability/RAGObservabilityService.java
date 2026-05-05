package io.github.imzmq.interview.knowledge.application.observability;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.config.observability.ObservabilitySwitchProperties;
import io.github.imzmq.interview.core.trace.RAGTraceContext;
import io.github.imzmq.interview.entity.knowledge.RagTraceDO;
import io.github.imzmq.interview.entity.knowledge.RagTraceNodeDO;
import io.github.imzmq.interview.mapper.knowledge.RagTraceMapper;
import io.github.imzmq.interview.mapper.knowledge.RagTraceNodeMapper;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeDefinitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG 可观测性服务。
 *
 * <p>职责说明：</p>
 * <p>1. 记录链路节点的开始/结束状态，并维护当前线程内的 Trace 节点栈。</p>
 * <p>2. 将已完成的 Trace 汇总与节点明细持久化到数据库，支持历史回放。</p>
 * <p>3. 对外提供最近 Trace、Trace 详情与概览统计，优先读取数据库历史，失败时回退内存。</p>
 */
@Service
public class RAGObservabilityService {

    private static final Logger logger = LoggerFactory.getLogger(RAGObservabilityService.class);
    private static final int MAX_TRACES = 300;
    private static final int MAX_TRACE_SCAN_LIMIT = 1_000;
    private static final int DEFAULT_RECENT_SUMMARY_SCAN_LIMIT = 1_000;
    private static final int DEFAULT_RETRIEVAL_LATENCY_WINDOW_LIMIT = 200;
    private static final int MAX_RETRIEVAL_LATENCY_WINDOW_HOURS = 24 * 7;
    private static final long SLOW_TRACE_THRESHOLD_MS = 20_000L;
    private static final long SLOW_FIRST_TOKEN_THRESHOLD_MS = 3_000L;
    private static final Pattern RETRIEVED_DOCS_PATTERN = Pattern.compile("(\\d+)\\s+docs?\\s+retrieved");
    private static final ZoneId TRACE_ZONE = ZoneId.systemDefault();

    /**
     * 已完成 Trace 的内存历史，用于数据库不可用时的安全回退。
     */
    private final Deque<RAGTrace> allTraces = new ConcurrentLinkedDeque<>();

    /**
     * 当前活跃 Trace，按 traceId 存放。
     */
    private final Map<String, RAGTrace> activeTraces = new java.util.concurrent.ConcurrentHashMap<>();

    private final ObservabilitySwitchProperties observabilitySwitchProperties;
    private final RagTraceMapper ragTraceMapper;
    private final RagTraceNodeMapper ragTraceNodeMapper;
    private final RagTraceEventBus ragTraceEventBus;

    /**
     * Spring 正常注入使用的构造器。
     *
     * @param observabilitySwitchProperties 可观测开关配置
     * @param ragTraceMapper Trace 汇总表 Mapper，缺失时自动退化为纯内存模式
     * @param ragTraceNodeMapper Trace 节点表 Mapper，缺失时自动退化为纯内存模式
     */
    @Autowired
    public RAGObservabilityService(
            ObservabilitySwitchProperties observabilitySwitchProperties,
            @Nullable RagTraceMapper ragTraceMapper,
            @Nullable RagTraceNodeMapper ragTraceNodeMapper,
            @Nullable RagTraceEventBus ragTraceEventBus
    ) {
        this.observabilitySwitchProperties = observabilitySwitchProperties;
        this.ragTraceMapper = ragTraceMapper;
        this.ragTraceNodeMapper = ragTraceNodeMapper;
        this.ragTraceEventBus = ragTraceEventBus;
    }

    /**
     * 与历史构造器签名兼容，默认不开启实时事件发布。
     *
     * @param observabilitySwitchProperties 可观测开关配置
     * @param ragTraceMapper Trace 汇总表 Mapper
     * @param ragTraceNodeMapper Trace 节点表 Mapper
     */
    public RAGObservabilityService(
            ObservabilitySwitchProperties observabilitySwitchProperties,
            @Nullable RagTraceMapper ragTraceMapper,
            @Nullable RagTraceNodeMapper ragTraceNodeMapper
    ) {
        this(observabilitySwitchProperties, ragTraceMapper, ragTraceNodeMapper, null);
    }

    /**
     * 单测和轻量场景下使用的纯内存构造器。
     *
     * @param observabilitySwitchProperties 可观测开关配置
     */
    public RAGObservabilityService(ObservabilitySwitchProperties observabilitySwitchProperties) {
        this(observabilitySwitchProperties, null, null, null);
    }

    /**
     * 开始一个链路节点，并把节点压入当前线程的 Trace 栈。
     *
     * @param traceId Trace ID
     * @param nodeId 节点 ID
     * @param parentNodeId 父节点 ID
     * @param nodeType 节点类型
     * @param nodeName 节点名称
     */
    public void startNode(String traceId, String nodeId, String parentNodeId, String nodeType, String nodeName) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return;
        }
        String safeTraceId = normalizeTraceId(traceId);
        RAGTrace trace = activeTraces.computeIfAbsent(safeTraceId, id -> new RAGTrace(id, Instant.now()));
        RAGTraceNode node = new RAGTraceNode(nodeId, parentNodeId, nodeType, nodeName, Instant.now());
        trace.addNode(node);
        publishNodeEvent(safeTraceId, "node_started", node, trace);
        RAGTraceContext.pushNode(nodeId);
    }

    /**
     * 结束一个链路节点。
     *
     * @param traceId Trace ID
     * @param nodeId 节点 ID
     * @param inputSummary 输入摘要
     * @param outputSummary 输出摘要
     * @param errorSummary 错误摘要
     */
    public void endNode(String traceId, String nodeId, String inputSummary, String outputSummary, String errorSummary) {
        endNode(traceId, nodeId, inputSummary, outputSummary, errorSummary, null, null);
    }

    /**
     * 结束一个链路节点，并在根节点结束时归档整条 Trace。
     *
     * @param traceId Trace ID
     * @param nodeId 节点 ID
     * @param inputSummary 输入摘要
     * @param outputSummary 输出摘要
     * @param errorSummary 错误摘要
     * @param metrics 节点结构化指标
     */
    public void endNode(String traceId, String nodeId, String inputSummary, String outputSummary, String errorSummary, NodeMetrics metrics) {
        endNode(traceId, nodeId, inputSummary, outputSummary, errorSummary, metrics, null);
    }

    /**
     * 结束一个链路节点，并补充结构化详情。
     *
     * @param traceId Trace ID
     * @param nodeId 节点 ID
     * @param inputSummary 输入摘要
     * @param outputSummary 输出摘要
     * @param errorSummary 错误摘要
     * @param metrics 节点结构化指标
     * @param details 节点结构化详情
     */
    public void endNode(String traceId,
                        String nodeId,
                        String inputSummary,
                        String outputSummary,
                        String errorSummary,
                        NodeMetrics metrics,
                        NodeDetails details) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return;
        }
        String safeTraceId = normalizeTraceId(traceId);
        try {
            RAGTrace trace = activeTraces.get(safeTraceId);
            if (trace == null) {
                return;
            }
            RAGTraceNode node = trace.findNode(nodeId);
            if (node == null) {
                return;
            }
            node.complete(Instant.now(), inputSummary, outputSummary, errorSummary, metrics, details);
            publishNodeEvent(safeTraceId, "node_finished", node, trace);
            if (node.parentNodeId() == null || node.parentNodeId().isBlank()) {
                publishTraceFinishedEvent(safeTraceId, trace);
                archiveTrace(safeTraceId, trace);
            }
        } finally {
            RAGTraceContext.removeNode(nodeId);
        }
    }

    /**
     * 获取最近完成的 Trace 列表。
     *
     * @param limit 返回条数上限
     * @return 最近 Trace 列表
     */
    public List<RAGTrace> listRecent(int limit) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return List.of();
        }
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 200);
        return resolveCompletedTraces(safeLimit);
    }

    /**
     * 获取运行中的 Trace 列表（活动态快照）。
     *
     * @param limit 返回条数上限
     * @return 活动态 Trace 列表
     */
    public List<RAGTrace> listActive(int limit) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return List.of();
        }
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 100);
        return activeTraces.values().stream()
                .sorted(Comparator.comparing(RAGTrace::startTime).reversed())
                .limit(safeLimit)
                .toList();
    }

    /**
     * 获取指定 Trace 的详情。
     *
     * @param traceId Trace ID
     * @return Trace 详情；不存在时返回 null
     */
    public RAGTrace getTraceDetail(String traceId) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return null;
        }
        String safeTraceId = normalizeTraceId(traceId);
        if (safeTraceId.isBlank()) {
            return null;
        }
        RAGTrace activeTrace = activeTraces.get(safeTraceId);
        if (activeTrace != null) {
            return activeTrace;
        }
        RAGTrace memoryTrace = allTraces.stream()
                .filter(trace -> safeTraceId.equals(trace.traceId()))
                .findFirst()
                .orElse(null);
        if (memoryTrace != null) {
            return memoryTrace;
        }
        return loadTraceDetailFromPersistence(safeTraceId);
    }

    /**
     * 计算 RAG 观测概览。
     *
     * @return 概览统计结果
     */
    public Map<String, Object> getOverview() {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return Map.ofEntries(
                    Map.entry("enabled", false),
                    Map.entry("traceCount", 0),
                    Map.entry("avgLatencyMs", 0),
                    Map.entry("avgRetrievedDocs", 0.0),
                    Map.entry("cacheHitRate", "0.0%"),
                    Map.entry("p95LatencyMs", 0),
                    Map.entry("successRate", "0.0%"),
                    Map.entry("failedTraceCount", 0),
                    Map.entry("activeTraceCount", 0),
                    Map.entry("slowTraceCount", 0),
                    Map.entry("riskyTraceCount", 0),
                    Map.entry("fallbackTraceCount", 0),
                    Map.entry("emptyRetrievalTraceCount", 0),
                    Map.entry("statusCounts", Map.of()),
                    Map.entry("riskTagCounts", Map.of()),
                    Map.entry("trendBuckets", List.of()),
                    Map.entry("alertLevel", "NONE"),
                    Map.entry("alertTags", List.of()),
                    Map.entry("alertSummary", "no_alerts")
            );
        }
        List<RAGTrace> traces = resolveCompletedTraces(DEFAULT_RECENT_SUMMARY_SCAN_LIMIT);
        if (traces.isEmpty()) {
            return Map.ofEntries(
                    Map.entry("enabled", true),
                    Map.entry("traceCount", 0),
                    Map.entry("avgLatencyMs", 0),
                    Map.entry("avgRetrievedDocs", 0.0),
                    Map.entry("cacheHitRate", "0.0%"),
                    Map.entry("p95LatencyMs", 0),
                    Map.entry("successRate", "0.0%"),
                    Map.entry("failedTraceCount", 0),
                    Map.entry("activeTraceCount", activeTraces.size()),
                    Map.entry("slowTraceCount", 0),
                    Map.entry("riskyTraceCount", 0),
                    Map.entry("fallbackTraceCount", 0),
                    Map.entry("emptyRetrievalTraceCount", 0),
                    Map.entry("statusCounts", Map.of()),
                    Map.entry("riskTagCounts", Map.of()),
                    Map.entry("trendBuckets", List.of()),
                    Map.entry("alertLevel", activeTraces.isEmpty() ? "NONE" : "INFO"),
                    Map.entry("alertTags", activeTraces.isEmpty() ? List.of() : List.of("active_traces_present")),
                    Map.entry("alertSummary", activeTraces.isEmpty() ? "no_alerts" : "active_traces_present")
            );
        }

        double avgLatency = traces.stream()
                .mapToLong(RAGTrace::getDurationMs)
                .average()
                .orElse(0.0D);
        double avgDocs = traces.stream()
                .flatMap(trace -> trace.nodes().stream())
                .filter(node -> "RETRIEVAL".equals(node.nodeType()))
                .mapToInt(this::resolveRetrievedDocs)
                .average()
                .orElse(0.0D);
        List<Long> sortedDurations = traces.stream()
                .map(RAGTrace::getDurationMs)
                .sorted()
                .toList();
        int p95Index = Math.max(0, (int) Math.ceil(sortedDurations.size() * 0.95D) - 1);
        long p95Latency = sortedDurations.get(p95Index);
        long failedTraceCount = traces.stream()
                .filter(trace -> "FAILED".equals(resolveTraceStatus(trace)))
                .count();
        List<TraceSummary> summaries = traces.stream()
                .map(this::buildTraceSummary)
                .filter(Objects::nonNull)
                .toList();
        long slowTraceCount = summaries.stream()
                .filter(summary -> Boolean.TRUE.equals(summary.slowTrace()))
                .count();
        long riskyTraceCount = summaries.stream()
                .filter(summary -> summary.riskCount() != null && summary.riskCount() > 0)
                .count();
        long fallbackTraceCount = summaries.stream()
                .filter(summary -> summary.riskTags() != null && summary.riskTags().contains("fallback_triggered"))
                .count();
        long emptyRetrievalTraceCount = summaries.stream()
                .filter(summary -> summary.riskTags() != null && summary.riskTags().contains("retrieval_empty"))
                .count();
        Map<String, Long> statusCounts = buildStatusCounts(summaries);
        Map<String, Long> riskTagCounts = buildRiskTagCounts(summaries);
        List<Map<String, Object>> trendBuckets = buildTrendBuckets(summaries);
        List<String> alertTags = buildOverviewAlertTags(
                activeTraces.size(),
                failedTraceCount,
                slowTraceCount,
                fallbackTraceCount,
                emptyRetrievalTraceCount,
                trendBuckets,
                0.0,
                0.0,
                0.0,
                0
        );
        String alertLevel = resolveAlertLevel(alertTags);
        double successRate = ((double) (traces.size() - failedTraceCount) / traces.size()) * 100.0D;

        return Map.ofEntries(
                Map.entry("enabled", true),
                Map.entry("traceCount", traces.size()),
                Map.entry("avgLatencyMs", (int) avgLatency),
                Map.entry("avgRetrievedDocs", String.format(Locale.ROOT, "%.1f", avgDocs)),
                Map.entry("cacheHitRate", "N/A"),
                Map.entry("p95LatencyMs", p95Latency),
                Map.entry("successRate", String.format(Locale.ROOT, "%.1f%%", successRate)),
                Map.entry("failedTraceCount", failedTraceCount),
                Map.entry("activeTraceCount", activeTraces.size()),
                Map.entry("slowTraceCount", slowTraceCount),
                Map.entry("riskyTraceCount", riskyTraceCount),
                Map.entry("fallbackTraceCount", fallbackTraceCount),
                Map.entry("emptyRetrievalTraceCount", emptyRetrievalTraceCount),
                Map.entry("statusCounts", statusCounts),
                Map.entry("riskTagCounts", riskTagCounts),
                Map.entry("trendBuckets", trendBuckets),
                Map.entry("alertLevel", alertLevel),
                Map.entry("alertTags", alertTags),
                Map.entry("alertSummary", alertTags.isEmpty() ? "no_alerts" : String.join(",", alertTags))
        );
    }

    private List<String> buildOverviewAlertTags(int activeTraceCount,
                                                long failedTraceCount,
                                                long slowTraceCount,
                                                long fallbackTraceCount,
                                                long emptyRetrievalTraceCount,
                                                List<Map<String, Object>> trendBuckets,
                                                double satisfactionRate,
                                                double latencyVsYesterdayPct,
                                                double successRateChangePct,
                                                long feedbackTotal) {
        List<String> tags = new ArrayList<>();
        if (activeTraceCount >= 5) {
            tags.add("high_active_trace_load");
        } else if (activeTraceCount > 0) {
            tags.add("active_traces_present");
        }
        if (failedTraceCount >= 3) {
            tags.add("failed_traces_elevated");
        }
        if (slowTraceCount >= 5) {
            tags.add("slow_traces_elevated");
        }
        if (fallbackTraceCount >= 3) {
            tags.add("fallback_rate_elevated");
        }
        if (emptyRetrievalTraceCount >= 3) {
            tags.add("empty_retrieval_elevated");
        }
        if (hasRisingTrend(trendBuckets, "risky")) {
            tags.add("degrading_risky_trend");
        }
        if (hasRisingTrend(trendBuckets, "slow")) {
            tags.add("degrading_slow_trend");
        }
        if (hasRisingTrend(trendBuckets, "failed")) {
            tags.add("degrading_failed_trend");
        }
        if (satisfactionRate >= 0 && satisfactionRate < 0.5 && feedbackTotal >= 5) {
            tags.add("satisfaction_dropped");
        }
        if (latencyVsYesterdayPct > 50) {
            tags.add("latency_degrading");
        }
        if (successRateChangePct < -5) {
            tags.add("success_rate_degrading");
        }
        return tags;
    }

    private boolean hasRisingTrend(List<Map<String, Object>> trendBuckets, String key) {
        if (trendBuckets == null || trendBuckets.size() < 2) {
            return false;
        }
        List<Long> values = trendBuckets.stream()
                .map(bucket -> toLong(bucket.get(key)))
                .filter(Objects::nonNull)
                .toList();
        if (values.size() < 2) {
            return false;
        }
        long latest = values.get(values.size() - 1);
        long previous = values.get(values.size() - 2);
        return latest > previous && latest > 0;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private String resolveAlertLevel(List<String> alertTags) {
        if (alertTags == null || alertTags.isEmpty()) {
            return "NONE";
        }
        boolean hasCritical = alertTags.stream().anyMatch(tag ->
                "failed_traces_elevated".equals(tag)
                        || "degrading_failed_trend".equals(tag)
                        || "high_active_trace_load".equals(tag)
        );
        if (hasCritical) {
            return "HIGH";
        }
        boolean hasWarning = alertTags.stream().anyMatch(tag ->
                "slow_traces_elevated".equals(tag)
                        || "fallback_rate_elevated".equals(tag)
                        || "empty_retrieval_elevated".equals(tag)
                        || "degrading_risky_trend".equals(tag)
                        || "degrading_slow_trend".equals(tag)
                        || "satisfaction_dropped".equals(tag)
                        || "latency_degrading".equals(tag)
                        || "success_rate_degrading".equals(tag)
        );
        if (hasWarning) {
            return "MEDIUM";
        }
        return "INFO";
    }

    private List<Map<String, Object>> buildTrendBuckets(List<TraceSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        List<TraceSummary> datedSummaries = summaries.stream()
                .filter(summary -> summary.endedAt() != null || summary.startedAt() != null)
                .sorted(Comparator.comparing(summary -> summary.endedAt() != null ? summary.endedAt() : summary.startedAt()))
                .toList();
        if (datedSummaries.isEmpty()) {
            return List.of();
        }
        int bucketCount = Math.min(8, datedSummaries.size());
        Instant earliest = resolveTrendTimestamp(datedSummaries.get(0));
        Instant latest = resolveTrendTimestamp(datedSummaries.get(datedSummaries.size() - 1));
        if (earliest == null || latest == null) {
            return List.of();
        }
        long spanMs = Math.max(1L, java.time.Duration.between(earliest, latest).toMillis() + 1L);
        long bucketWidthMs = Math.max(1L, (long) Math.ceil((double) spanMs / bucketCount));
        List<List<TraceSummary>> partitions = new ArrayList<>();
        List<Instant> bucketStarts = new ArrayList<>();
        List<Instant> bucketEnds = new ArrayList<>();
        for (int i = 0; i < bucketCount; i++) {
            partitions.add(new ArrayList<>());
            Instant bucketStart = earliest.plusMillis(bucketWidthMs * i);
            Instant bucketEnd = i == bucketCount - 1
                    ? latest
                    : earliest.plusMillis(Math.max(0L, bucketWidthMs * (i + 1) - 1L));
            bucketStarts.add(bucketStart);
            bucketEnds.add(bucketEnd);
        }
        for (TraceSummary summary : datedSummaries) {
            Instant timestamp = resolveTrendTimestamp(summary);
            if (timestamp == null) {
                continue;
            }
            long offsetMs = Math.max(0L, java.time.Duration.between(earliest, timestamp).toMillis());
            int bucketIndex = Math.min(bucketCount - 1, (int) (offsetMs / bucketWidthMs));
            partitions.get(bucketIndex).add(summary);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < partitions.size(); i++) {
            List<TraceSummary> bucket = partitions.get(i);
            if (bucket.isEmpty()) {
                continue;
            }
            long slow = bucket.stream().filter(item -> Boolean.TRUE.equals(item.slowTrace())).count();
            long fallback = bucket.stream().filter(item -> item.riskTags() != null && item.riskTags().contains("fallback_triggered")).count();
            long emptyRetrieval = bucket.stream().filter(item -> item.riskTags() != null && item.riskTags().contains("retrieval_empty")).count();
            long failed = bucket.stream().filter(item -> "FAILED".equals(normalizeDisplayStatus(item))).count();
            long risky = bucket.stream().filter(item -> item.riskCount() != null && item.riskCount() > 0).count();
            result.add(Map.ofEntries(
                    Map.entry("bucket", i + 1),
                    Map.entry("label", formatTrendBucketLabel(bucketStarts.get(i), bucketEnds.get(i), i + 1)),
                    Map.entry("startAt", bucketStarts.get(i)),
                    Map.entry("endAt", bucketEnds.get(i)),
                    Map.entry("total", bucket.size()),
                    Map.entry("slow", slow),
                    Map.entry("fallback", fallback),
                    Map.entry("emptyRetrieval", emptyRetrieval),
                    Map.entry("failed", failed),
                    Map.entry("risky", risky)
            ));
        }
        return result;
    }

    private Instant resolveTrendTimestamp(TraceSummary summary) {
        return summary.endedAt() != null ? summary.endedAt() : summary.startedAt();
    }

    private String formatTrendBucketLabel(Instant startAt, Instant endAt, int bucketNumber) {
        if (startAt == null || endAt == null) {
            return String.format(Locale.ROOT, "B%d", bucketNumber);
        }
        LocalDateTime start = LocalDateTime.ofInstant(startAt, TRACE_ZONE);
        LocalDateTime end = LocalDateTime.ofInstant(endAt, TRACE_ZONE);
        return String.format(
                Locale.ROOT,
                "%02d:%02d-%02d:%02d",
                start.getHour(),
                start.getMinute(),
                end.getHour(),
                end.getMinute()
        );
    }

    private Map<String, Long> buildStatusCounts(List<TraceSummary> summaries) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("SUCCESS", 0L);
        counts.put("SLOW", 0L);
        counts.put("FAILED", 0L);
        counts.put("RUNNING", 0L);
        for (TraceSummary summary : summaries) {
            String status = normalizeDisplayStatus(summary);
            counts.compute(status, (key, value) -> value == null ? 1L : value + 1L);
        }
        return counts;
    }

    private Map<String, Long> buildRiskTagCounts(List<TraceSummary> summaries) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TraceSummary summary : summaries) {
            if (summary.riskTags() == null) {
                continue;
            }
            for (String riskTag : summary.riskTags()) {
                if (riskTag == null || riskTag.isBlank()) {
                    continue;
                }
                counts.compute(riskTag, (key, value) -> value == null ? 1L : value + 1L);
            }
        }
        return counts;
    }

    private String normalizeDisplayStatus(TraceSummary summary) {
        String traceStatus = summary.traceStatus() == null ? "" : summary.traceStatus().trim().toUpperCase(Locale.ROOT);
        if ("COMPLETED".equals(traceStatus)) {
            return Boolean.TRUE.equals(summary.slowTrace()) ? "SLOW" : "SUCCESS";
        }
        if (traceStatus.isBlank()) {
            return "UNKNOWN";
        }
        return traceStatus;
    }

    private int normalizeLatencyWindowLimit(@Nullable Integer limit) {
        int raw = limit == null ? DEFAULT_RETRIEVAL_LATENCY_WINDOW_LIMIT : limit;
        if (raw <= 0) {
            return DEFAULT_RETRIEVAL_LATENCY_WINDOW_LIMIT;
        }
        return Math.min(raw, MAX_TRACE_SCAN_LIMIT);
    }

    private Integer normalizeLatencyWindowHours(@Nullable Integer hours) {
        if (hours == null || hours <= 0) {
            return null;
        }
        return Math.min(hours, MAX_RETRIEVAL_LATENCY_WINDOW_HOURS);
    }

    private boolean isTraceWithinHours(RAGTrace trace, int hours) {
        Instant referenceTime = trace == null ? null : trace.getEffectiveEndTime();
        if (referenceTime == null && trace != null) {
            referenceTime = trace.getEffectiveStartTime();
        }
        if (referenceTime == null) {
            return false;
        }
        Instant cutoff = Instant.now().minus(java.time.Duration.ofHours(hours));
        return !referenceTime.isBefore(cutoff);
    }

    private long percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0L;
        }
        int index = Math.max(0, (int) Math.ceil(sortedValues.size() * percentile) - 1);
        return sortedValues.get(Math.min(index, sortedValues.size() - 1));
    }

    /**
     * 发布节点级事件到实时事件总线。
     *
     * @param traceId Trace ID
     * @param eventType 事件类型
     * @param node 节点
     * @param trace Trace
     */
    private void publishNodeEvent(String traceId, String eventType, RAGTraceNode node, RAGTrace trace) {
        if (ragTraceEventBus == null) {
            return;
        }
        TraceSummary summary = buildTraceSummary(trace);
        ragTraceEventBus.publish(traceId, new RagTraceEventBus.RagTraceStreamEvent(
                traceId,
                eventType,
                LocalDateTime.now(),
                node,
                new RagTraceEventBus.TraceSummary(
                        summary == null ? resolveTraceStatus(trace) : summary.traceStatus(),
                        summary == null ? trace.getDurationMs() : summary.businessDurationMs(),
                        summary == null ? trace.nodes().size() : summary.nodeCount(),
                        summary == null ? 0 : summary.retrievalNodeCount()
                )
        ));
    }

    public List<TraceSummary> listRecentSummaries(int limit) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return List.of();
        }
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, MAX_TRACE_SCAN_LIMIT);
        int scanLimit = Math.max(safeLimit, DEFAULT_RECENT_SUMMARY_SCAN_LIMIT);
        return resolveCompletedTraces(scanLimit).stream()
                .map(this::buildTraceSummary)
                .limit(safeLimit)
                .toList();
    }

    public List<TraceSummary> listActiveSummaries(int limit) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return List.of();
        }
        return listActive(limit).stream()
                .map(this::buildTraceSummary)
                .toList();
    }

    public TraceDetailView getTraceDetailView(String traceId) {
        RAGTrace trace = getTraceDetail(traceId);
        if (trace == null) {
            return null;
        }
        return new TraceDetailView(trace, buildTraceSummary(trace));
    }

    /**
     * 统计检索节点延迟分位数（P95/P99）。
     *
     * <p>统计口径：</p>
     * <p>1. 只统计已完成 Trace 中 `nodeType=RETRIEVAL` 的节点耗时。</p>
     * <p>2. 支持按最近 N 条 Trace（limit）或最近 N 小时（hours）窗口统计。</p>
     *
     * @param limit 最近 Trace 数量窗口（hours 为空时生效）
     * @param hours 最近小时数窗口（优先级高于 limit）
     * @return 检索延迟统计结果
     */
    public RetrievalLatencyStats getRetrievalLatencyStats(@Nullable Integer limit, @Nullable Integer hours) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return new RetrievalLatencyStats(0L, 0L, 0, "disabled", null, null);
        }
        Integer safeHours = normalizeLatencyWindowHours(hours);
        int safeLimit = normalizeLatencyWindowLimit(limit);
        List<RAGTrace> completedTraces = safeHours == null
                ? resolveCompletedTraces(safeLimit)
                : resolveCompletedTraces(MAX_TRACE_SCAN_LIMIT).stream()
                .filter(trace -> isTraceWithinHours(trace, safeHours))
                .toList();

        List<Long> sortedDurations = completedTraces.stream()
                .flatMap(trace -> trace.nodes().stream())
                .filter(node -> "RETRIEVAL".equals(node.nodeType()))
                .map(RAGTraceNode::durationMs)
                .filter(Objects::nonNull)
                .map(value -> Math.max(0L, value))
                .sorted()
                .toList();
        if (sortedDurations.isEmpty()) {
            return new RetrievalLatencyStats(
                    0L,
                    0L,
                    0,
                    safeHours == null ? "limit" : "hours",
                    safeHours == null ? safeLimit : null,
                    safeHours
            );
        }
        return new RetrievalLatencyStats(
                percentile(sortedDurations, 0.95D),
                percentile(sortedDurations, 0.99D),
                sortedDurations.size(),
                safeHours == null ? "limit" : "hours",
                safeHours == null ? safeLimit : null,
                safeHours
        );
    }

    public String getActiveRootNodeId(String traceId) {
        String safeTraceId = normalizeTraceId(traceId);
        if (safeTraceId.isBlank()) {
            return null;
        }
        RAGTrace trace = activeTraces.get(safeTraceId);
        if (trace == null) {
            return null;
        }
        RAGTraceNode rootNode = findRootNode(trace);
        return rootNode == null ? null : rootNode.nodeId();
    }

    /**
     * 发布链路结束事件（成功/失败）。
     *
     * @param traceId Trace ID
     * @param trace Trace
     */
    private void publishTraceFinishedEvent(String traceId, RAGTrace trace) {
        if (ragTraceEventBus == null) {
            return;
        }
        TraceSummary summary = buildTraceSummary(trace);
        String traceStatus = summary == null ? resolveTraceStatus(trace) : summary.traceStatus();
        String eventType = "FAILED".equals(traceStatus) ? "trace_failed" : "trace_finished";
        ragTraceEventBus.publish(traceId, new RagTraceEventBus.RagTraceStreamEvent(
                traceId,
                eventType,
                LocalDateTime.now(),
                null,
                new RagTraceEventBus.TraceSummary(
                        traceStatus,
                        summary == null ? trace.getDurationMs() : summary.businessDurationMs(),
                        summary == null ? trace.nodes().size() : summary.nodeCount(),
                        summary == null ? 0 : summary.retrievalNodeCount()
                )
        ));
    }

    /**
     * 将根节点已结束的 Trace 归档到内存历史，并尽力写入数据库。
     *
     * @param traceId Trace ID
     * @param trace 已完成的 Trace
     */
    private void archiveTrace(String traceId, RAGTrace trace) {
        activeTraces.remove(traceId);
        allTraces.addLast(trace);
        while (allTraces.size() > MAX_TRACES) {
            allTraces.removeFirst();
        }
        persistTrace(trace);
    }

    /**
     * 汇总内存与数据库中的已完成 Trace。
     *
     * @param limit 返回条数上限
     * @return 归并后的 Trace 列表
     */
    private List<RAGTrace> resolveCompletedTraces(int limit) {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, MAX_TRACE_SCAN_LIMIT);
        Map<String, RAGTrace> merged = new LinkedHashMap<>();
        for (RAGTrace trace : loadRecentFromPersistence(Math.max(safeLimit, 20))) {
            merged.put(trace.traceId(), trace);
        }
        for (RAGTrace trace : allTraces) {
            merged.put(trace.traceId(), trace);
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(RAGTrace::startTime).reversed())
                .limit(safeLimit)
                .toList();
    }

    /**
     * 从数据库读取最近的 Trace 历史。
     *
     * @param limit 返回条数上限
     * @return 持久化历史；数据库不可用时返回空列表
     */
    private List<RAGTrace> loadRecentFromPersistence(int limit) {
        if (!persistenceAvailable()) {
            return List.of();
        }
        try {
            List<RagTraceDO> traceRows = ragTraceMapper.selectList(new LambdaQueryWrapper<RagTraceDO>()
                    .orderByDesc(RagTraceDO::getStartedAt)
                    .last("LIMIT " + Math.max(1, Math.min(limit, MAX_TRACE_SCAN_LIMIT))));
            if (traceRows == null || traceRows.isEmpty()) {
                return List.of();
            }
            List<String> traceIds = traceRows.stream()
                    .map(RagTraceDO::getTraceId)
                    .filter(item -> item != null && !item.isBlank())
                    .toList();
            Map<String, List<RagTraceNodeDO>> nodeGroup = loadNodeGroup(traceIds);
            return traceRows.stream()
                    .map(row -> toDomainTrace(row, nodeGroup.getOrDefault(row.getTraceId(), List.of())))
                    .toList();
        } catch (Exception e) {
            logger.warn("加载持久化 RAG Trace 列表失败，回退内存历史。原因: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 从数据库读取单条 Trace 详情。
     *
     * @param traceId Trace ID
     * @return Trace 详情；不存在时返回 null
     */
    private RAGTrace loadTraceDetailFromPersistence(String traceId) {
        if (!persistenceAvailable()) {
            return null;
        }
        try {
            RagTraceDO traceRow = ragTraceMapper.selectOne(new LambdaQueryWrapper<RagTraceDO>()
                    .eq(RagTraceDO::getTraceId, traceId)
                    .last("LIMIT 1"));
            if (traceRow == null) {
                return null;
            }
            List<RagTraceNodeDO> nodeRows = ragTraceNodeMapper.selectList(new LambdaQueryWrapper<RagTraceNodeDO>()
                    .eq(RagTraceNodeDO::getTraceId, traceId)
                    .orderByAsc(RagTraceNodeDO::getStartedAt, RagTraceNodeDO::getId));
            return toDomainTrace(traceRow, nodeRows);
        } catch (Exception e) {
            logger.warn("加载持久化 RAG Trace 详情失败，traceId={}，原因: {}", traceId, e.getMessage());
            return null;
        }
    }

    /**
     * 加载一批 Trace 对应的节点列表，并按 traceId 分组。
     *
     * @param traceIds Trace ID 列表
     * @return 节点分组结果
     */
    private Map<String, List<RagTraceNodeDO>> loadNodeGroup(List<String> traceIds) {
        if (!persistenceAvailable() || traceIds == null || traceIds.isEmpty()) {
            return Map.of();
        }
        List<RagTraceNodeDO> nodeRows = ragTraceNodeMapper.selectList(new LambdaQueryWrapper<RagTraceNodeDO>()
                .in(RagTraceNodeDO::getTraceId, traceIds)
                .orderByAsc(RagTraceNodeDO::getStartedAt, RagTraceNodeDO::getId));
        return nodeRows.stream().collect(Collectors.groupingBy(
                RagTraceNodeDO::getTraceId,
                LinkedHashMap::new,
                Collectors.toList()
        ));
    }

    /**
     * 将内存 Trace 持久化到数据库。
     *
     * @param trace 已完成的 Trace
     */
    private void persistTrace(RAGTrace trace) {
        if (!persistenceAvailable() || trace == null) {
            return;
        }
        try {
            RagTraceDO traceDO = toTraceDO(trace);
            RagTraceDO existing = ragTraceMapper.selectOne(new LambdaQueryWrapper<RagTraceDO>()
                    .eq(RagTraceDO::getTraceId, trace.traceId())
                    .last("LIMIT 1"));
            if (existing == null) {
                ragTraceMapper.insert(traceDO);
            } else {
                traceDO.setId(existing.getId());
                ragTraceMapper.updateById(traceDO);
            }

            ragTraceNodeMapper.delete(new LambdaQueryWrapper<RagTraceNodeDO>()
                    .eq(RagTraceNodeDO::getTraceId, trace.traceId()));
            for (RagTraceNodeDO nodeDO : toTraceNodeDOs(trace)) {
                ragTraceNodeMapper.insert(nodeDO);
            }
        } catch (Exception e) {
            logger.warn("持久化 RAG Trace 失败，traceId={}，已保留内存历史。原因: {}", trace.traceId(), e.getMessage());
        }
    }

    /**
     * 将内存 Trace 转为汇总表实体。
     *
     * @param trace 内存 Trace
     * @return 汇总实体
     */
    private RagTraceDO toTraceDO(RAGTrace trace) {
        RAGTraceNode rootNode = findRootNode(trace);
        Instant effectiveStartTime = trace.getEffectiveStartTime();
        Instant effectiveEndTime = trace.getEffectiveEndTime();
        int retrievalNodeCount = (int) trace.nodes().stream().filter(node -> "RETRIEVAL".equals(node.nodeType())).count();
        int totalRetrievedDocs = trace.nodes().stream()
                .filter(node -> "RETRIEVAL".equals(node.nodeType()))
                .mapToInt(this::resolveRetrievedDocs)
                .sum();
        int maxRetrievedDocs = trace.nodes().stream()
                .filter(node -> "RETRIEVAL".equals(node.nodeType()))
                .mapToInt(this::resolveRetrievedDocs)
                .max()
                .orElse(0);
        boolean webFallbackUsed = trace.nodes().stream()
                .map(RAGTraceNode::metrics)
                .filter(Objects::nonNull)
                .map(NodeMetrics::webFallbackUsed)
                .anyMatch(Boolean.TRUE::equals);

        RagTraceDO traceDO = new RagTraceDO();
        traceDO.setTraceId(trace.traceId());
        traceDO.setRootNodeId(rootNode == null ? "" : rootNode.nodeId());
        traceDO.setRootNodeType(rootNode == null ? "" : rootNode.nodeType());
        traceDO.setRootNodeName(rootNode == null ? "" : rootNode.nodeName());
        traceDO.setTraceStatus(resolveTraceStatus(trace));
        traceDO.setNodeCount(trace.nodes().size());
        traceDO.setRetrievalNodeCount(retrievalNodeCount);
        traceDO.setTotalRetrievedDocs(totalRetrievedDocs);
        traceDO.setMaxRetrievedDocs(maxRetrievedDocs);
        traceDO.setWebFallbackUsed(webFallbackUsed);
        traceDO.setStartedAt(toLocalDateTime(effectiveStartTime));
        traceDO.setEndedAt(toLocalDateTime(effectiveEndTime));
        traceDO.setDurationMs(trace.getDurationMs());
        return traceDO;
    }

    /**
     * 将内存 Trace 转为节点明细实体列表。
     *
     * @param trace 内存 Trace
     * @return 节点明细实体列表
     */
    private List<RagTraceNodeDO> toTraceNodeDOs(RAGTrace trace) {
        List<RagTraceNodeDO> result = new ArrayList<>();
        for (RAGTraceNode node : trace.nodes()) {
            RagTraceNodeDO nodeDO = new RagTraceNodeDO();
            nodeDO.setTraceId(trace.traceId());
            nodeDO.setNodeId(node.nodeId());
            nodeDO.setParentNodeId(node.parentNodeId());
            nodeDO.setNodeType(node.nodeType());
            nodeDO.setNodeName(node.nodeName());
            nodeDO.setNodeStatus(node.status());
            nodeDO.setInputSummary(node.inputSummary());
            nodeDO.setOutputSummary(node.outputSummary());
            nodeDO.setErrorSummary(node.errorSummary());
            nodeDO.setRetrievedDocs(node.metrics() == null ? null : node.metrics().retrievedDocs());
            nodeDO.setWebFallbackUsed(node.metrics() == null ? null : node.metrics().webFallbackUsed());
            nodeDO.setStartedAt(toLocalDateTime(node.startTime()));
            nodeDO.setEndedAt(toLocalDateTime(node.endTime()));
            nodeDO.setDurationMs(node.durationMs());
            result.add(nodeDO);
        }
        return result;
    }

    /**
     * 将数据库汇总与节点明细还原成领域 Trace。
     *
     * @param traceDO 汇总实体
     * @param nodeRows 节点明细实体列表
     * @return 领域 Trace
     */
    private RAGTrace toDomainTrace(RagTraceDO traceDO, List<RagTraceNodeDO> nodeRows) {
        RAGTrace trace = new RAGTrace(
                traceDO.getTraceId(),
                toInstant(traceDO.getStartedAt()) == null ? Instant.now() : toInstant(traceDO.getStartedAt())
        );
        for (RagTraceNodeDO nodeRow : nodeRows) {
            trace.addNode(toDomainNode(nodeRow));
        }
        return trace;
    }

    /**
     * 将数据库节点明细还原为领域节点。
     *
     * @param nodeDO 节点明细实体
     * @return 领域节点
     */
    private RAGTraceNode toDomainNode(RagTraceNodeDO nodeDO) {
        return RAGTraceNode.restore(
                nodeDO.getNodeId(),
                nodeDO.getParentNodeId(),
                nodeDO.getNodeType(),
                nodeDO.getNodeName(),
                toInstant(nodeDO.getStartedAt()),
                toInstant(nodeDO.getEndedAt()),
                nodeDO.getInputSummary(),
                nodeDO.getOutputSummary(),
                nodeDO.getErrorSummary(),
                new NodeMetrics(nodeDO.getRetrievedDocs(), nodeDO.getWebFallbackUsed()),
                resolveNodeDetails(nodeDO),
                nodeDO.getNodeStatus()
        );
    }

    private NodeDetails resolveNodeDetails(RagTraceNodeDO nodeDO) {
        if (nodeDO == null) {
            return null;
        }
        String outputSummary = nodeDO.getOutputSummary();
        String retrievalMode = extractSummaryToken(outputSummary, "mode");
        Boolean cacheHit = extractBooleanToken(outputSummary, "cacheHit");
        Integer retrievedDocCount = firstNonNull(
                extractIntegerToken(outputSummary, "retrievedDocs"),
                extractIntegerToken(outputSummary, "docCount"),
                nodeDO.getRetrievedDocs()
        );
        List<String> retrievedDocumentRefs = extractDocRefs(outputSummary);
        Integer imageSearchCount = extractIntegerToken(outputSummary, "imageCount");
        Integer imageEventCount = extractIntegerToken(outputSummary, "imageEventCount");
        Integer webSearchCount = extractIntegerToken(outputSummary, "webSearchCount");
        String fallbackReason = extractSummaryToken(outputSummary, "fallbackReason");
        Long firstTokenMs = extractLongToken(outputSummary, "firstTokenMs");
        Long completionMs = extractLongToken(outputSummary, "completionMs");
        String modelName = extractSummaryToken(outputSummary, "model");

        if (retrievalMode == null
                && cacheHit == null
                && retrievedDocCount == null
                && retrievedDocumentRefs.isEmpty()
                && imageSearchCount == null
                && imageEventCount == null
                && webSearchCount == null
                && fallbackReason == null
                && firstTokenMs == null
                && completionMs == null
                && modelName == null) {
            return null;
        }
        return new NodeDetails(
                1,
                retrievalMode,
                null,
                cacheHit,
                retrievedDocCount,
                retrievedDocumentRefs,
                webSearchCount,
                firstNonNull(imageSearchCount, imageEventCount),
                fallbackReason,
                modelName,
                firstTokenMs,
                completionMs
        );
    }

    /**
     * 解析节点中的召回文档数。
     *
     * @param node Trace 节点
     * @return 召回文档数
     */
    private int resolveRetrievedDocs(RAGTraceNode node) {
        if (node.metrics() != null && node.metrics().retrievedDocs() != null) {
            return Math.max(0, node.metrics().retrievedDocs());
        }
        String outputSummary = node.outputSummary();
        if (outputSummary == null || outputSummary.isBlank()) {
            return 0;
        }
        Matcher matcher = RETRIEVED_DOCS_PATTERN.matcher(outputSummary);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * 解析整条 Trace 的总体状态。
     *
     * @param trace Trace
     * @return 汇总状态
     */
    private String resolveTraceStatus(RAGTrace trace) {
        return resolveTraceStatus(trace, false);
    }

    private String resolveTraceStatus(RAGTrace trace, boolean active) {
        if (active) {
            return "RUNNING";
        }
        boolean hasFailure = trace.nodes().stream().anyMatch(node -> "FAILED".equals(node.status()));
        return hasFailure ? "FAILED" : "COMPLETED";
    }

    /**
     * 获取 Trace 的根节点。
     *
     * @param trace Trace
     * @return 根节点；不存在时返回首个节点
     */
    private RAGTraceNode findRootNode(RAGTrace trace) {
        if (trace == null || trace.nodes().isEmpty()) {
            return null;
        }
        return trace.nodes().stream()
                .filter(node -> node.parentNodeId() == null || node.parentNodeId().isBlank())
                .findFirst()
                .orElse(trace.nodes().get(0));
    }

    /**
     * 判断当前是否具备数据库持久化能力。
     *
     * @return true 表示可用
     */
    private boolean persistenceAvailable() {
        return ragTraceMapper != null && ragTraceNodeMapper != null;
    }

    /**
     * 归一化 Trace ID，避免空值污染索引。
     *
     * @param traceId 原始 Trace ID
     * @return 归一化后的 Trace ID
     */
    private String normalizeTraceId(String traceId) {
        return traceId == null ? "" : traceId.trim();
    }

    private TraceSummary buildTraceSummary(RAGTrace trace) {
        if (trace == null) {
            return null;
        }
        List<RAGTraceNode> nodes = trace.nodes();
        RAGTraceNode rootNode = findRootNode(trace);
        String traceStatus = resolveTraceStatus(trace, activeTraces.containsKey(trace.traceId()));
        long businessDurationMs = Math.max(0L, trace.getDurationMs());
        long connectionDurationMs = rootNode == null ? businessDurationMs : Math.max(0L, rootNode.durationMs());
        int retrievalNodeCount = (int) nodes.stream()
                .filter(node -> "RETRIEVAL".equals(node.nodeType()))
                .count();
        int retrievedDocCount = nodes.stream()
                .filter(node -> "RETRIEVAL".equals(node.nodeType()))
                .mapToInt(this::resolveRetrievedDocs)
                .sum();
        int fallbackCount = (int) nodes.stream()
                .filter(this::isFallbackNode)
                .count();
        int failedNodeCount = (int) nodes.stream()
                .filter(node -> {
                    String status = node.status();
                    return "FAILED".equals(status) || "ERROR".equals(status) || "TIMEOUT".equals(status);
                })
                .count();
        Long firstTokenMs = nodes.stream()
                .map(RAGTraceNode::details)
                .filter(Objects::nonNull)
                .map(NodeDetails::firstTokenMs)
                .filter(Objects::nonNull)
                .min(Long::compareTo)
                .orElse(null);
        Long streamDispatchMs = nodes.stream()
                .filter(node -> TraceNodeDefinitions.STREAM_DISPATCH.nodeName().equals(node.nodeName()))
                .map(RAGTraceNode::details)
                .filter(Objects::nonNull)
                .map(NodeDetails::completionMs)
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(null);
        List<String> riskTags = buildRiskTags(
                traceStatus,
                businessDurationMs,
                firstTokenMs,
                retrievedDocCount,
                fallbackCount
        );
        Instant startedAt = trace.getEffectiveStartTime();
        Instant endedAt = trace.getEffectiveEndTime();
        return new TraceSummary(
                trace.traceId(),
                traceStatus,
                businessDurationMs,
                connectionDurationMs,
                firstTokenMs,
                streamDispatchMs,
                retrievedDocCount,
                retrievedDocCount,
                fallbackCount,
                failedNodeCount,
                businessDurationMs >= SLOW_TRACE_THRESHOLD_MS,
                riskTags,
                riskTags.size(),
                nodes.size(),
                retrievalNodeCount,
                startedAt,
                endedAt
        );
    }

    private List<String> buildRiskTags(String traceStatus,
                                       long businessDurationMs,
                                       Long firstTokenMs,
                                       int retrievedDocCount,
                                       int fallbackCount) {
        List<String> tags = new ArrayList<>();
        if (businessDurationMs >= SLOW_TRACE_THRESHOLD_MS) {
            tags.add("slow_trace");
        }
        if (firstTokenMs != null && firstTokenMs >= SLOW_FIRST_TOKEN_THRESHOLD_MS) {
            tags.add("slow_first_token");
        }
        if (fallbackCount > 0) {
            tags.add("fallback_triggered");
        }
        boolean shouldCheckRetrievalEmpty = !"CANCELLED".equals(traceStatus) && !"RUNNING".equals(traceStatus);
        if (shouldCheckRetrievalEmpty && retrievedDocCount <= 0) {
            tags.add("retrieval_empty");
        }
        return List.copyOf(tags);
    }

    private boolean isFallbackNode(RAGTraceNode node) {
        if (node == null) {
            return false;
        }
        String nodeType = node.nodeType();
        if (nodeType != null && nodeType.toUpperCase(Locale.ROOT).contains("FALLBACK")) {
            return true;
        }
        return node.details() != null
                && node.details().fallbackReason() != null
                && !node.details().fallbackReason().isBlank();
    }

    private Integer extractIntegerToken(String summary, String key) {
        String token = extractSummaryToken(summary, key);
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Boolean extractBooleanToken(String summary, String key) {
        String token = extractSummaryToken(summary, key);
        if (token == null || token.isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(token);
    }

    private Long extractLongToken(String summary, String key) {
        String token = extractSummaryToken(summary, key);
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String extractSummaryToken(String summary, String key) {
        if (summary == null || summary.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        Pattern pattern = Pattern.compile(Pattern.quote(key) + "=([^,}\\]]+)");
        Matcher matcher = pattern.matcher(summary);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        return value == null ? null : value.trim();
    }

    private List<String> extractDocRefs(String summary) {
        if (summary == null || summary.isBlank()) {
            return List.of();
        }
        Matcher matcher = Pattern.compile("docRefs=\\[(.*)]\\}?$", Pattern.DOTALL).matcher(summary);
        if (!matcher.find()) {
            return List.of();
        }
        String body = matcher.group(1);
        if (body == null || body.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(body.split(",\\s*(?=(?:\\[[a-z]+]|[A-Za-z]:\\\\|[A-Za-z0-9_./-]+\\s*\\|))"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 将 Instant 转为本地时间，便于写入 DATETIME 字段。
     *
     * @param instant Instant 时间
     * @return 本地时间
     */
    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, TRACE_ZONE);
    }

    /**
     * 将本地时间还原为 Instant。
     *
     * @param localDateTime 本地时间
     * @return Instant 时间
     */
    private Instant toInstant(LocalDateTime localDateTime) {
        return localDateTime == null ? null : localDateTime.atZone(TRACE_ZONE).toInstant();
    }

    /**
     * 节点结构化指标。
     *
     * @param retrievedDocs 召回文档数
     * @param webFallbackUsed 是否触发 Web fallback
     */
    public record NodeMetrics(Integer retrievedDocs, Boolean webFallbackUsed) {
    }

    public record TraceSummary(
            String traceId,
            String traceStatus,
            Long businessDurationMs,
            Long connectionDurationMs,
            Long firstTokenMs,
            Long streamDispatchMs,
            Integer retrievedDocCount,
            Integer displayedDocCount,
            Integer fallbackCount,
            Integer failedNodeCount,
            Boolean slowTrace,
            List<String> riskTags,
            Integer riskCount,
            Integer nodeCount,
            Integer retrievalNodeCount,
            Instant startedAt,
            Instant endedAt,
            Integer followUpCount
    ) {
        public TraceSummary(
                String traceId, String traceStatus, Long businessDurationMs,
                Long connectionDurationMs, Long firstTokenMs, Long streamDispatchMs,
                Integer retrievedDocCount, Integer displayedDocCount,
                Integer fallbackCount, Integer failedNodeCount, Boolean slowTrace,
                List<String> riskTags, Integer riskCount, Integer nodeCount,
                Integer retrievalNodeCount, Instant startedAt, Instant endedAt
        ) {
            this(traceId, traceStatus, businessDurationMs, connectionDurationMs,
                    firstTokenMs, streamDispatchMs, retrievedDocCount, displayedDocCount,
                    fallbackCount, failedNodeCount, slowTrace, riskTags, riskCount,
                    nodeCount, retrievalNodeCount, startedAt, endedAt, null);
        }
    }

    public record TraceDetailView(
            RAGTrace trace,
            TraceSummary summary
    ) {
    }

    public record RetrievalLatencyStats(
            long p95LatencyMs,
            long p99LatencyMs,
            int sampleSize,
            String windowMode,
            @Nullable Integer windowLimit,
            @Nullable Integer windowHours
    ) {
    }

    /**
     * 节点结构化详情，供前端与后续告警/汇总逻辑直接消费。
     */
    public record NodeDetails(
            Integer schemaVersion,
            String retrievalMode,
            String intentRoute,
            Boolean cacheHit,
            Integer retrievedDocCount,
            List<String> retrievedDocumentRefs,
            Integer webSearchCount,
            Integer imageSearchCount,
            String fallbackReason,
            String modelName,
            Long firstTokenMs,
            Long completionMs
    ) {
        public NodeDetails {
            schemaVersion = schemaVersion == null || schemaVersion <= 0 ? 1 : schemaVersion;
            retrievalMode = retrievalMode == null || retrievalMode.isBlank() ? null : retrievalMode.trim();
            intentRoute = intentRoute == null || intentRoute.isBlank() ? null : intentRoute.trim();
            retrievedDocCount = retrievedDocCount == null ? null : Math.max(0, retrievedDocCount);
            retrievedDocumentRefs = retrievedDocumentRefs == null ? List.of() : List.copyOf(retrievedDocumentRefs);
            webSearchCount = webSearchCount == null ? null : Math.max(0, webSearchCount);
            imageSearchCount = imageSearchCount == null ? null : Math.max(0, imageSearchCount);
            fallbackReason = fallbackReason == null || fallbackReason.isBlank() ? null : fallbackReason.trim();
            modelName = modelName == null || modelName.isBlank() ? null : modelName.trim();
            firstTokenMs = firstTokenMs == null ? null : Math.max(0L, firstTokenMs);
            completionMs = completionMs == null ? null : Math.max(0L, completionMs);
        }
    }

    /**
     * RAG Trace 领域模型。
     *
     * @param traceId Trace ID
     * @param startTime Trace 开始时间
     * @param nodes Trace 节点列表
     */
    public record RAGTrace(
            String traceId,
            Instant startTime,
            List<RAGTraceNode> nodes
    ) {
        public RAGTrace(String traceId, Instant startTime) {
            this(traceId, startTime, new ArrayList<>());
        }

        /**
         * 向 Trace 中追加节点。
         *
         * @param node Trace 节点
         */
        public void addNode(RAGTraceNode node) {
            nodes.add(node);
        }

        /**
         * 按节点 ID 查找节点。
         *
         * @param nodeId 节点 ID
         * @return 匹配节点；不存在时返回 null
         */
        public RAGTraceNode findNode(String nodeId) {
            return nodes.stream().filter(node -> node.nodeId().equals(nodeId)).findFirst().orElse(null);
        }

        /**
         * 计算整条 Trace 的总耗时。
         *
         * @return Trace 总耗时
         */
        public long getDurationMs() {
            if (nodes.isEmpty()) {
                return 0L;
            }
            Instant effectiveStart = getEffectiveStartTime();
            Instant effectiveEnd = getEffectiveEndTime();
            if (effectiveStart == null || effectiveEnd == null) {
                return 0L;
            }
            return java.time.Duration.between(effectiveStart, effectiveEnd).toMillis();
        }

        public Instant getEffectiveStartTime() {
            if (nodes.isEmpty()) {
                return null;
            }
            List<RAGTraceNode> businessNodes = businessNodes();
            return businessNodes.isEmpty()
                    ? rootOrFirstNode().startTime()
                    : businessNodes.stream()
                    .map(RAGTraceNode::startTime)
                    .filter(Objects::nonNull)
                    .min(Instant::compareTo)
                    .orElse(rootOrFirstNode().startTime());
        }

        public Instant getEffectiveEndTime() {
            if (nodes.isEmpty()) {
                return null;
            }
            List<RAGTraceNode> businessNodes = businessNodes();
            if (businessNodes.isEmpty()) {
                return rootOrFirstNode().endTime();
            }
            return businessNodes.stream()
                    .map(RAGTraceNode::endTime)
                    .filter(Objects::nonNull)
                    .max(Instant::compareTo)
                    .orElse(rootOrFirstNode().endTime());
        }

        private List<RAGTraceNode> businessNodes() {
            return nodes.stream()
                    .filter(node -> node.parentNodeId() != null && !node.parentNodeId().isBlank())
                    .filter(node -> !"TERMINAL".equals(node.nodeType()))
                    .filter(node -> !"ERROR".equals(node.nodeName()))
                    .toList();
        }

        private RAGTraceNode rootOrFirstNode() {
            return nodes.stream()
                    .filter(node -> node.parentNodeId() == null || node.parentNodeId().isBlank())
                    .findFirst()
                    .orElse(nodes.get(0));
        }
    }

    /**
     * RAG Trace 节点领域模型。
     */
    public static final class RAGTraceNode {
        private final String nodeId;
        private final String parentNodeId;
        private final String nodeType;
        private final String nodeName;
        private final Instant startTime;
        private Instant endTime;
        private String inputSummary;
        private String outputSummary;
        private String errorSummary;
        private NodeMetrics metrics;
        private NodeDetails details;
        private String status = "RUNNING";

        /**
         * 创建一个运行中的节点。
         *
         * @param nodeId 节点 ID
         * @param parentNodeId 父节点 ID
         * @param nodeType 节点类型
         * @param nodeName 节点名称
         * @param startTime 开始时间
         */
        public RAGTraceNode(String nodeId, String parentNodeId, String nodeType, String nodeName, Instant startTime) {
            this.nodeId = nodeId;
            this.parentNodeId = parentNodeId;
            this.nodeType = nodeType;
            this.nodeName = nodeName;
            this.startTime = startTime;
        }

        /**
         * 从持久化结果恢复节点对象。
         *
         * @param nodeId 节点 ID
         * @param parentNodeId 父节点 ID
         * @param nodeType 节点类型
         * @param nodeName 节点名称
         * @param startTime 开始时间
         * @param endTime 结束时间
         * @param inputSummary 输入摘要
         * @param outputSummary 输出摘要
         * @param errorSummary 错误摘要
         * @param metrics 结构化指标
         * @param status 节点状态
         * @return 恢复后的节点对象
         */
        public static RAGTraceNode restore(
                String nodeId,
                String parentNodeId,
                String nodeType,
                String nodeName,
                Instant startTime,
                Instant endTime,
                String inputSummary,
                String outputSummary,
                String errorSummary,
                NodeMetrics metrics,
                NodeDetails details,
                String status
        ) {
            RAGTraceNode node = new RAGTraceNode(
                    nodeId,
                    parentNodeId,
                    nodeType,
                    nodeName,
                    startTime == null ? Instant.now() : startTime
            );
            node.endTime = endTime;
            node.inputSummary = inputSummary;
            node.outputSummary = outputSummary;
            node.errorSummary = errorSummary;
            node.metrics = metrics;
            node.details = details;
            node.status = status == null || status.isBlank()
                    ? ((errorSummary == null || errorSummary.isBlank()) ? "COMPLETED" : "FAILED")
                    : status;
            return node;
        }

        public static RAGTraceNode restore(
                String nodeId,
                String parentNodeId,
                String nodeType,
                String nodeName,
                Instant startTime,
                Instant endTime,
                String inputSummary,
                String outputSummary,
                String errorSummary,
                NodeMetrics metrics,
                String status
        ) {
            return restore(
                    nodeId,
                    parentNodeId,
                    nodeType,
                    nodeName,
                    startTime,
                    endTime,
                    inputSummary,
                    outputSummary,
                    errorSummary,
                    metrics,
                    null,
                    status
            );
        }

        /**
         * 将节点标记为结束。
         *
         * @param endTime 结束时间
         * @param inputSummary 输入摘要
         * @param outputSummary 输出摘要
         * @param errorSummary 错误摘要
         * @param metrics 结构化指标
         */
        public void complete(Instant endTime,
                             String inputSummary,
                             String outputSummary,
                             String errorSummary,
                             NodeMetrics metrics,
                             NodeDetails details) {
            this.endTime = endTime;
            this.inputSummary = inputSummary;
            this.outputSummary = outputSummary;
            this.errorSummary = errorSummary;
            this.metrics = metrics;
            this.details = details;
            this.status = (errorSummary == null || errorSummary.isBlank()) ? "COMPLETED" : "FAILED";
        }

        /**
         * 计算节点耗时。
         *
         * @return 节点耗时，单位毫秒
         */
        public long durationMs() {
            if (endTime == null) {
                return java.time.Duration.between(startTime, Instant.now()).toMillis();
            }
            return java.time.Duration.between(startTime, endTime).toMillis();
        }

        public String nodeId() {
            return nodeId;
        }

        /**
         * 提供标准 Bean Getter，便于 Spring MVC/Jackson 将节点详情稳定序列化为 JSON。
         *
         * @return 节点 ID
         */
        public String getNodeId() {
            return nodeId;
        }

        public String parentNodeId() {
            return parentNodeId;
        }

        /**
         * 提供标准 Getter，避免控制层返回 Trace 详情时出现空 Bean 序列化异常。
         *
         * @return 父节点 ID
         */
        public String getParentNodeId() {
            return parentNodeId;
        }

        public String nodeType() {
            return nodeType;
        }

        /**
         * @return 节点类型
         */
        public String getNodeType() {
            return nodeType;
        }

        public String nodeName() {
            return nodeName;
        }

        /**
         * @return 节点名称
         */
        public String getNodeName() {
            return nodeName;
        }

        public Instant startTime() {
            return startTime;
        }

        /**
         * @return 节点开始时间
         */
        public Instant getStartTime() {
            return startTime;
        }

        public Instant endTime() {
            return endTime;
        }

        /**
         * @return 节点结束时间
         */
        public Instant getEndTime() {
            return endTime;
        }

        public String inputSummary() {
            return inputSummary;
        }

        /**
         * @return 输入摘要
         */
        public String getInputSummary() {
            return inputSummary;
        }

        public String outputSummary() {
            return outputSummary;
        }

        /**
         * @return 输出摘要
         */
        public String getOutputSummary() {
            return outputSummary;
        }

        public String errorSummary() {
            return errorSummary;
        }

        /**
         * @return 错误摘要
         */
        public String getErrorSummary() {
            return errorSummary;
        }

        public NodeMetrics metrics() {
            return metrics;
        }

        /**
         * @return 结构化指标
         */
        public NodeMetrics getMetrics() {
            return metrics;
        }

        public NodeDetails details() {
            return details;
        }

        /**
         * @return 节点结构化详情
         */
        public NodeDetails getDetails() {
            return details;
        }

        public String status() {
            return status;
        }

        /**
         * @return 节点状态
         */
        public String getStatus() {
            return status;
        }

        /**
         * 直接暴露节点耗时，便于前端与控制层接口统一消费。
         *
         * @return 节点耗时，单位毫秒
         */
        public long getDurationMs() {
            return durationMs();
        }
    }
}









