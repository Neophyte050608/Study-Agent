package com.example.interview.service;

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
    private final Deque<TraceRecord> traces = new ArrayDeque<>();

    public synchronized void record(TraceRecord record) {
        if (record == null) {
            return;
        }
        traces.addLast(record);
        while (traces.size() > MAX_TRACES) {
            traces.removeFirst();
        }
    }

    public synchronized List<TraceRecord> listRecent(int limit) {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 200);
        List<TraceRecord> all = new ArrayList<>(traces);
        return all.stream()
                .sorted(Comparator.comparing(TraceRecord::timestamp).reversed())
                .limit(safeLimit)
                .toList();
    }

    public synchronized java.util.Map<String, Object> getOverview() {
        if (traces.isEmpty()) {
            return java.util.Map.of(
                    "avgLatencyMs", 0,
                    "avgRetrievedDocs", 0.0,
                    "cacheHitRate", "0.0%"
            );
        }
        double avgLatency = traces.stream().mapToLong(TraceRecord::latencyMs).average().orElse(0.0);
        double avgDocs = traces.stream().mapToInt(TraceRecord::retrievedCount).average().orElse(0.0);
        // 这里模拟一个缓存命中率逻辑，比如耗时小于50ms认为是命中缓存
        long hitCount = traces.stream().filter(t -> t.latencyMs() < 50).count();
        double hitRate = (double) hitCount / traces.size() * 100;

        return java.util.Map.of(
                "avgLatencyMs", (int) avgLatency,
                "avgRetrievedDocs", String.format("%.1f", avgDocs),
                "cacheHitRate", String.format("%.1f%%", hitRate)
        );
    }

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
