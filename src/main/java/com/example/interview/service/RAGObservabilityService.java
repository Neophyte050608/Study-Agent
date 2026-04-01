package com.example.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.interview.config.ObservabilitySwitchProperties;
import com.example.interview.core.RAGTraceContext;
import com.example.interview.entity.RagTraceDO;
import com.example.interview.entity.RagTraceNodeDO;
import com.example.interview.mapper.RagTraceMapper;
import com.example.interview.mapper.RagTraceNodeMapper;
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
            @Nullable RagTraceNodeMapper ragTraceNodeMapper
    ) {
        this.observabilitySwitchProperties = observabilitySwitchProperties;
        this.ragTraceMapper = ragTraceMapper;
        this.ragTraceNodeMapper = ragTraceNodeMapper;
    }

    /**
     * 单测和轻量场景下使用的纯内存构造器。
     *
     * @param observabilitySwitchProperties 可观测开关配置
     */
    public RAGObservabilityService(ObservabilitySwitchProperties observabilitySwitchProperties) {
        this(observabilitySwitchProperties, null, null);
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
        endNode(traceId, nodeId, inputSummary, outputSummary, errorSummary, null);
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
            node.complete(Instant.now(), inputSummary, outputSummary, errorSummary, metrics);
            if (node.parentNodeId() == null || node.parentNodeId().isBlank()) {
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
            return Map.of(
                    "enabled", false,
                    "avgLatencyMs", 0,
                    "avgRetrievedDocs", 0.0,
                    "cacheHitRate", "0.0%"
            );
        }
        List<RAGTrace> traces = resolveCompletedTraces(MAX_TRACES);
        if (traces.isEmpty()) {
            return Map.of(
                    "enabled", true,
                    "avgLatencyMs", 0,
                    "avgRetrievedDocs", 0.0,
                    "cacheHitRate", "0.0%"
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

        return Map.of(
                "enabled", true,
                "avgLatencyMs", (int) avgLatency,
                "avgRetrievedDocs", String.format(Locale.ROOT, "%.1f", avgDocs),
                "cacheHitRate", "N/A"
        );
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
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, MAX_TRACES);
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
                    .last("LIMIT " + Math.max(1, Math.min(limit, MAX_TRACES))));
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
        traceDO.setStartedAt(toLocalDateTime(trace.startTime()));
        traceDO.setEndedAt(rootNode == null ? null : toLocalDateTime(rootNode.endTime()));
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
                nodeDO.getNodeStatus()
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
            return nodes.stream()
                    .filter(node -> node.parentNodeId() == null || node.parentNodeId().isBlank())
                    .findFirst()
                    .orElse(nodes.get(0))
                    .durationMs();
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
            node.status = status == null || status.isBlank()
                    ? ((errorSummary == null || errorSummary.isBlank()) ? "COMPLETED" : "FAILED")
                    : status;
            return node;
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
        public void complete(Instant endTime, String inputSummary, String outputSummary, String errorSummary, NodeMetrics metrics) {
            this.endTime = endTime;
            this.inputSummary = inputSummary;
            this.outputSummary = outputSummary;
            this.errorSummary = errorSummary;
            this.metrics = metrics;
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
