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
import java.util.List;
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
    public void aggregatePreviousHour() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        LocalDateTime windowStart = now.minusHours(1);
        LocalDateTime windowEnd = now;

        try {
            List<RagTraceDO> traces = ragTraceMapper.selectList(new LambdaQueryWrapper<RagTraceDO>()
                    .ge(RagTraceDO::getEndedAt, windowStart)
                    .lt(RagTraceDO::getEndedAt, windowEnd));

            int traceCount = traces.size();
            if (traceCount == 0) {
                logger.debug("No traces in window [{}, {}), skipping snapshot", windowStart, windowEnd);
                return;
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
            snapshot.setSnapshotHour(windowStart);
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
        } catch (Exception e) {
            logger.error("Failed to aggregate metrics snapshot for window [{}, {}): {}",
                    windowStart, windowEnd, e.getMessage(), e);
        }
    }

    private void cleanOldSnapshots() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        snapshotMapper.delete(new LambdaQueryWrapper<RagMetricsSnapshotDO>()
                .lt(RagMetricsSnapshotDO::getSnapshotHour, cutoff));
    }
}
