package com.example.interview.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

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

    public record TraceRecord(
            Instant timestamp,
            String query,
            int retrievedCount,
            int evidenceCount,
            int citationsCount,
            int conflictsCount,
            int score,
            boolean fallbackUsed,
            long latencyMs
    ) {
    }
}
