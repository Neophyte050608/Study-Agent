package com.example.interview.service;

import com.example.interview.config.ObservabilitySwitchProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * RAG 可观测性服务。
 * 
 * 职责：
 * 1. 链路记录：记录每次 RAG 调用的耗时、检索到的文档数、引用的证据数及最终得分。
 * 2. 性能统计：计算平均延迟、平均召回文档数及模拟缓存命中率。
 * 3. 故障回溯：通过最近的 Trace 记录，帮助排查“为什么 AI 没引用到笔记”或“为什么回答偏离”等问题。
 */
@Service
public class RAGObservabilityService {

    private static final int MAX_TRACES = 300;
    private final Deque<RAGTrace> allTraces = new ArrayDeque<>();
    private final java.util.Map<String, RAGTrace> activeTraces = new java.util.concurrent.ConcurrentHashMap<>();
    private final ObservabilitySwitchProperties observabilitySwitchProperties;

    public RAGObservabilityService(ObservabilitySwitchProperties observabilitySwitchProperties) {
        this.observabilitySwitchProperties = observabilitySwitchProperties;
    }

    /**
     * 开始一个链路节点。
     */
    public synchronized void startNode(String traceId, String nodeId, String parentNodeId, String nodeType, String nodeName) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) return;
        
        RAGTrace trace = activeTraces.computeIfAbsent(traceId, id -> new RAGTrace(id, Instant.now()));
        RAGTraceNode node = new RAGTraceNode(nodeId, parentNodeId, nodeType, nodeName, Instant.now());
        trace.addNode(node);
    }

    /**
     * 结束一个链路节点。
     */
    public synchronized void endNode(String traceId, String nodeId, String inputSummary, String outputSummary, String errorSummary) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) return;

        RAGTrace trace = activeTraces.get(traceId);
        if (trace != null) {
            RAGTraceNode node = trace.findNode(nodeId);
            if (node != null) {
                node.complete(Instant.now(), inputSummary, outputSummary, errorSummary);
                // 如果是根节点完成，则移入历史列表
                if (node.parentNodeId() == null || node.parentNodeId().isBlank()) {
                    activeTraces.remove(traceId);
                    allTraces.addLast(trace);
                    while (allTraces.size() > MAX_TRACES) {
                        allTraces.removeFirst();
                    }
                }
            }
        }
    }

    public synchronized List<RAGTrace> listRecent(int limit) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return List.of();
        }
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 200);
        List<RAGTrace> all = new ArrayList<>(allTraces);
        return all.stream()
                .sorted(Comparator.comparing(RAGTrace::startTime).reversed())
                .limit(safeLimit)
                .toList();
    }

    public synchronized RAGTrace getTraceDetail(String traceId) {
        // 先查活跃的，再查历史的
        RAGTrace trace = activeTraces.get(traceId);
        if (trace != null) return trace;
        
        return allTraces.stream()
                .filter(t -> t.traceId().equals(traceId))
                .findFirst()
                .orElse(null);
    }

    public synchronized java.util.Map<String, Object> getOverview() {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return java.util.Map.of(
                    "enabled", false,
                    "avgLatencyMs", 0,
                    "avgRetrievedDocs", 0.0,
                    "cacheHitRate", "0.0%"
            );
        }
        if (allTraces.isEmpty()) {
            return java.util.Map.of(
                    "enabled", true,
                    "avgLatencyMs", 0,
                    "avgRetrievedDocs", 0.0,
                    "cacheHitRate", "0.0%"
            );
        }
        
        // 计算根节点的平均耗时
        double avgLatency = allTraces.stream()
                .mapToLong(t -> t.getDurationMs())
                .average()
                .orElse(0.0);
                
        // 尝试从检索节点提取召回数量
        double avgDocs = allTraces.stream()
                .flatMap(t -> t.nodes().stream())
                .filter(n -> "RETRIEVAL".equals(n.nodeType()))
                .mapToInt(n -> {
                    try {
                        // 假设摘要中存了数量，这里简单处理
                        return Integer.parseInt(n.outputSummary().split(" ")[0]);
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .average()
                .orElse(0.0);

        return java.util.Map.of(
                "enabled", true,
                "avgLatencyMs", (int) avgLatency,
                "avgRetrievedDocs", String.format("%.1f", avgDocs),
                "cacheHitRate", "N/A"
        );
    }

    // --- 数据模型 ---

    /**
     * RAG 追踪链路记录。
     */
    public record RAGTrace(
            String traceId,
            Instant startTime,
            List<RAGTraceNode> nodes
    ) {
        public RAGTrace(String traceId, Instant startTime) {
            this(traceId, startTime, new ArrayList<>());
        }

        public void addNode(RAGTraceNode node) {
            nodes.add(node);
        }

        public RAGTraceNode findNode(String nodeId) {
            return nodes.stream().filter(n -> n.nodeId().equals(nodeId)).findFirst().orElse(null);
        }

        public long getDurationMs() {
            if (nodes.isEmpty()) return 0;
            // 根节点的耗时即为整条链路耗时
            return nodes.get(0).durationMs();
        }
    }

    /**
     * RAG 追踪节点记录。
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
        private String status = "RUNNING";

        public RAGTraceNode(String nodeId, String parentNodeId, String nodeType, String nodeName, Instant startTime) {
            this.nodeId = nodeId;
            this.parentNodeId = parentNodeId;
            this.nodeType = nodeType;
            this.nodeName = nodeName;
            this.startTime = startTime;
        }

        public void complete(Instant endTime, String inputSummary, String outputSummary, String errorSummary) {
            this.endTime = endTime;
            this.inputSummary = inputSummary;
            this.outputSummary = outputSummary;
            this.errorSummary = errorSummary;
            this.status = (errorSummary == null || errorSummary.isBlank()) ? "COMPLETED" : "FAILED";
        }

        public long durationMs() {
            if (endTime == null) return java.time.Duration.between(startTime, Instant.now()).toMillis();
            return java.time.Duration.between(startTime, endTime).toMillis();
        }

        // Getters
        public String nodeId() { return nodeId; }
        public String parentNodeId() { return parentNodeId; }
        public String nodeType() { return nodeType; }
        public String nodeName() { return nodeName; }
        public Instant startTime() { return startTime; }
        public Instant endTime() { return endTime; }
        public String inputSummary() { return inputSummary; }
        public String outputSummary() { return outputSummary; }
        public String errorSummary() { return errorSummary; }
        public String status() { return status; }
    }

    // 兼容旧代码，保留但标记过时
    @Deprecated
    public synchronized void record(TraceRecord record) {
        // ... 原有逻辑可以映射到新模型，或者直接留空
    }

    @Deprecated
    public record TraceRecord(
            Instant timestamp,
            String query,
            int retrievedCount,
            int evidenceCount,
            int citationsCount,
            int conflictsCount,
            int score,
            boolean fallbackUsed,
            long latencyMs,
            int inputTokens,
            int outputTokens
    ) {
    }
}
