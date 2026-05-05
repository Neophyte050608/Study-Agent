package io.github.imzmq.interview.feedback.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.entity.knowledge.RagMetricsSnapshotDO;
import io.github.imzmq.interview.entity.knowledge.RagTraceDO;
import io.github.imzmq.interview.mapper.knowledge.RagMetricsSnapshotMapper;
import io.github.imzmq.interview.mapper.knowledge.RagTraceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MetricsSnapshotScheduler {

    private static final Logger logger = LoggerFactory.getLogger(MetricsSnapshotScheduler.class);
    private static final int RETENTION_DAYS = 90;

    private final RagTraceMapper ragTraceMapper;
    private final RagMetricsSnapshotMapper snapshotMapper;
    private final FeedbackApplicationService feedbackApplicationService;

    public MetricsSnapshotScheduler(RagTraceMapper ragTraceMapper,
                                    RagMetricsSnapshotMapper snapshotMapper,
                                    FeedbackApplicationService feedbackApplicationService) {
        this.ragTraceMapper = ragTraceMapper;
        this.snapshotMapper = snapshotMapper;
        this.feedbackApplicationService = feedbackApplicationService;
    }

    @Scheduled(cron = "0 5 * * * *")
    public void scheduledAggregate() {
        aggregatePreviousHour();
    }

    public Map<String, Object> aggregatePreviousHour() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        LocalDateTime windowStart = now.minusHours(1);
        LocalDateTime windowEnd = now;
        return aggregateWindow(windowStart, windowEnd);
    }

    public Map<String, Object> aggregateCurrentHour() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.truncatedTo(ChronoUnit.HOURS);
        return aggregateWindow(windowStart, now);
    }

    public Map<String, Object> backfillMissingSnapshots(int maxHours) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        int created = 0;
        int skipped = 0;
        int emptyHours = 0;
        int safeHours = Math.min(maxHours, 720);

        for (int h = 1; h <= safeHours; h++) {
            LocalDateTime hourStart = now.minusHours(h);
            LocalDateTime hourEnd = hourStart.plusHours(1);

            Long existingCount = snapshotMapper.selectCount(new LambdaQueryWrapper<RagMetricsSnapshotDO>()
                    .eq(RagMetricsSnapshotDO::getSnapshotHour, hourStart));
            if (existingCount != null && existingCount > 0) {
                skipped++;
                continue;
            }

            Map<String, Object> result = aggregateWindow(hourStart, hourEnd);
            if (Boolean.TRUE.equals(result.get("created"))) {
                created++;
            } else {
                emptyHours++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("created", created > 0);
        summary.put("snapshotsCreated", created);
        summary.put("snapshotsSkipped", skipped);
        summary.put("emptyHours", emptyHours);
        summary.put("totalHoursScanned", safeHours);
        if (created > 0) {
            summary.put("message", "回填完成：新增 " + created + " 个快照，跳过 " + skipped + " 个已有，" + emptyHours + " 小时无数据");
        } else if (skipped > 0) {
            summary.put("message", "最近 " + safeHours + " 小时内已有 " + skipped + " 个快照，无需新增");
        } else {
            summary.put("message", "最近 " + safeHours + " 小时内无 Trace 数据，未生成快照");
        }
        return summary;
    }

    private Map<String, Object> aggregateWindow(LocalDateTime windowStart, LocalDateTime windowEnd) {
        try {
            List<RagTraceDO> traces = ragTraceMapper.selectList(new LambdaQueryWrapper<RagTraceDO>()
                    .ge(RagTraceDO::getEndedAt, windowStart)
                    .lt(RagTraceDO::getEndedAt, windowEnd));

            int traceCount = traces.size();
            if (traceCount == 0) {
                logger.debug("No traces in window [{}, {}), skipping snapshot", windowStart, windowEnd);
                return Map.of("created", false, "message", "该时间段无 Trace 数据，未生成快照");
            }

            long totalDuration = traces.stream().mapToLong(t -> t.getDurationMs() == null ? 0 : t.getDurationMs()).sum();
            List<Long> sortedDurations = traces.stream()
                    .map(t -> t.getDurationMs() == null ? 0L : t.getDurationMs())
                    .sorted().toList();
            long p95DurationMs = sortedDurations.isEmpty() ? 0
                    : sortedDurations.get(Math.max(0, (int) Math.ceil(sortedDurations.size() * 0.95) - 1));

            long successCount = traces.stream().filter(t -> "COMPLETED".equals(t.getTraceStatus())).count();
            long failedCount = traces.stream()
                    .filter(t -> "FAILED".equals(t.getTraceStatus()) || "ERROR".equals(t.getTraceStatus()))
                    .count();
            long slowCount = traces.stream().filter(t -> t.getDurationMs() != null && t.getDurationMs() >= 20_000).count();
            long fallbackCount = traces.stream().filter(t -> Boolean.TRUE.equals(t.getWebFallbackUsed())).count();
            long emptyRetrievalCount = traces.stream()
                    .filter(t -> t.getTotalRetrievedDocs() == null || t.getTotalRetrievedDocs() == 0)
                    .count();

            double avgRetrievedDocs = traces.stream()
                    .mapToInt(t -> t.getTotalRetrievedDocs() == null ? 0 : t.getTotalRetrievedDocs())
                    .average().orElse(0.0);

            long thumbsUp = feedbackApplicationService.countByType(windowStart, windowEnd, "THUMBS_UP");
            long thumbsDown = feedbackApplicationService.countByType(windowStart, windowEnd, "THUMBS_DOWN");
            long copyCount = feedbackApplicationService.countByType(windowStart, windowEnd, "COPY");
            long totalFeedback = thumbsUp + thumbsDown;
            double satisfactionRate = totalFeedback > 0 ? (double) thumbsUp / totalFeedback : 0.0;

            RagMetricsSnapshotDO snapshot = new RagMetricsSnapshotDO();
            snapshot.setSnapshotId(UUID.randomUUID().toString());
            snapshot.setSnapshotHour(windowStart.truncatedTo(ChronoUnit.HOURS));
            snapshot.setTraceCount(traceCount);
            snapshot.setAvgDurationMs(traceCount > 0 ? totalDuration / traceCount : 0);
            snapshot.setP95DurationMs(p95DurationMs);
            snapshot.setSuccessCount((int) successCount);
            snapshot.setFailedCount((int) failedCount);
            snapshot.setSlowCount((int) slowCount);
            snapshot.setFallbackCount((int) fallbackCount);
            snapshot.setEmptyRetrievalCount((int) emptyRetrievalCount);
            snapshot.setAvgRetrievedDocs(Math.round(avgRetrievedDocs * 10.0) / 10.0);
            snapshot.setThumbsUpCount((int) thumbsUp);
            snapshot.setThumbsDownCount((int) thumbsDown);
            snapshot.setCopyCount((int) copyCount);
            snapshot.setSatisfactionRate(Math.round(satisfactionRate * 1000.0) / 1000.0);
            snapshot.setCreatedAt(LocalDateTime.now());

            snapshotMapper.insert(snapshot);
            logger.info("Metrics snapshot created for window [{}, {}): {} traces, satisfaction={}",
                    windowStart, windowEnd, traceCount, snapshot.getSatisfactionRate());

            cleanOldSnapshots();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("created", true);
            result.put("traceCount", traceCount);
            result.put("snapshotHour", snapshot.getSnapshotHour().toString());
            result.put("message", "快照生成成功，包含 " + traceCount + " 条 Trace");
            return result;
        } catch (Exception e) {
            logger.error("Failed to aggregate metrics snapshot for window [{}, {}): {}",
                    windowStart, windowEnd, e.getMessage(), e);
            return Map.of("created", false, "message", "快照生成失败: " + e.getMessage());
        }
    }

    private void cleanOldSnapshots() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        snapshotMapper.delete(new LambdaQueryWrapper<RagMetricsSnapshotDO>()
                .lt(RagMetricsSnapshotDO::getSnapshotHour, cutoff));
    }
}
