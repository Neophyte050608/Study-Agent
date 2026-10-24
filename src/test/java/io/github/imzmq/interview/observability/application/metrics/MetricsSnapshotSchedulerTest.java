package io.github.imzmq.interview.observability.application.metrics;

import io.github.imzmq.interview.feedback.application.contract.FeedbackMetricsQuery;
import io.github.imzmq.interview.feedback.application.contract.result.FeedbackMetrics;
import io.github.imzmq.interview.observability.infrastructure.persistence.RagMetricsSnapshotDO;
import io.github.imzmq.interview.observability.infrastructure.persistence.RagMetricsSnapshotMapper;
import io.github.imzmq.interview.observability.infrastructure.persistence.RagTraceDO;
import io.github.imzmq.interview.observability.infrastructure.persistence.RagTraceMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricsSnapshotSchedulerTest {

    @Test
    void aggregateCurrentHourUsesFeedbackContractAndPreservesSnapshotMetrics() {
        RagTraceMapper traceMapper = mock(RagTraceMapper.class);
        RagMetricsSnapshotMapper snapshotMapper = mock(RagMetricsSnapshotMapper.class);
        FeedbackMetricsQuery feedbackQuery = mock(FeedbackMetricsQuery.class);
        RagTraceDO fast = trace("COMPLETED", 100L, 4, false);
        RagTraceDO slowFallback = trace("FAILED", 20_000L, 0, true);
        when(traceMapper.selectList(any())).thenReturn(List.of(fast, slowFallback));
        when(feedbackQuery.summarize(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new FeedbackMetrics(3, 1, 2));
        MetricsSnapshotScheduler scheduler = new MetricsSnapshotScheduler(traceMapper, snapshotMapper, feedbackQuery);

        Map<String, Object> result = scheduler.aggregateCurrentHour();

        assertThat(result).containsEntry("created", true).containsEntry("traceCount", 2);
        ArgumentCaptor<RagMetricsSnapshotDO> captor = ArgumentCaptor.forClass(RagMetricsSnapshotDO.class);
        verify(snapshotMapper).insert(captor.capture());
        RagMetricsSnapshotDO snapshot = captor.getValue();
        assertThat(snapshot.getAvgDurationMs()).isEqualTo(10_050L);
        assertThat(snapshot.getP95DurationMs()).isEqualTo(20_000L);
        assertThat(snapshot.getSuccessCount()).isEqualTo(1);
        assertThat(snapshot.getFailedCount()).isEqualTo(1);
        assertThat(snapshot.getSlowCount()).isEqualTo(1);
        assertThat(snapshot.getFallbackCount()).isEqualTo(1);
        assertThat(snapshot.getEmptyRetrievalCount()).isEqualTo(1);
        assertThat(snapshot.getAvgRetrievedDocs()).isEqualTo(2.0D);
        assertThat(snapshot.getThumbsUpCount()).isEqualTo(3);
        assertThat(snapshot.getThumbsDownCount()).isEqualTo(1);
        assertThat(snapshot.getCopyCount()).isEqualTo(2);
        assertThat(snapshot.getSatisfactionRate()).isEqualTo(0.75D);
    }

    private static RagTraceDO trace(String status, long durationMs, int retrievedDocs, boolean fallback) {
        RagTraceDO trace = new RagTraceDO();
        trace.setTraceStatus(status);
        trace.setDurationMs(durationMs);
        trace.setTotalRetrievedDocs(retrievedDocs);
        trace.setWebFallbackUsed(fallback);
        return trace;
    }
}
