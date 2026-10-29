package io.github.imzmq.interview.chat.application.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import io.github.imzmq.interview.chat.infrastructure.milvus.ChatMemoryProjection;
import io.github.imzmq.interview.chat.infrastructure.milvus.ChatMemoryProjectionPort;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryOutboxDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryOutboxMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryProjectionWorkerTest {

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ChatMemoryOutboxClaimService claimService = mock(ChatMemoryOutboxClaimService.class);
    private final ChatMemoryItemMapper itemMapper = mock(ChatMemoryItemMapper.class);
    private final ChatMemorySourceMapper sourceMapper = mock(ChatMemorySourceMapper.class);
    private final ChatMemoryOutboxMapper outboxMapper = mock(ChatMemoryOutboxMapper.class);
    private final ChatMemoryProjectionPort projection = mock(ChatMemoryProjectionPort.class);
    private final ChatMemoryProperties properties = new ChatMemoryProperties();
    private ChatMemoryProjectionWorker worker;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setRpcTimeout(Duration.ofSeconds(10));
        properties.setProjectionMaxAttempts(10);
        worker = new ChatMemoryProjectionWorker(
                claimService, itemMapper, sourceMapper, outboxMapper, projection, properties,
                mock(ChatMemoryObservationPublisher.class), CLOCK);
    }

    @Test
    void currentActiveMemoryWithSourceIsUpsertedAndAcknowledged() {
        ChatMemoryOutboxDO event = event(2L, "UPSERT");
        when(itemMapper.selectById(10L)).thenReturn(item("ACTIVE", 2L));
        when(sourceMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(outboxMapper.completeEvent(eq("event-1"), eq("lease-1"), any())).thenReturn(1);

        worker.process(event);

        verify(projection).upsert(eq(new ChatMemoryProjection(
                "10:2", 10L, "user-1", 2L, "hash", "FACT", "content")),
                eq(Duration.ofSeconds(10)));
        verify(projection).deleteOtherVersions(
                "user-1", 10L, 2L, Duration.ofSeconds(10));
        verify(outboxMapper).completeEvent(eq("event-1"), eq("lease-1"), any());
    }

    @Test
    void missingTruthDeletesTenantScopedProjectionUsingOutboxOwner() {
        ChatMemoryOutboxDO event = event(2L, "DELETE");
        when(itemMapper.selectById(10L)).thenReturn(null);
        when(outboxMapper.completeEvent(eq("event-1"), eq("lease-1"), any())).thenReturn(1);

        worker.process(event);

        verify(projection).deleteMemory("user-1", 10L, Duration.ofSeconds(10));
        verify(outboxMapper).completeEvent(eq("event-1"), eq("lease-1"), any());
    }

    @Test
    void repairDeleteRemovesTheExactProjectionEvenWhenTruthOwnerOrVersionDiffers() {
        ChatMemoryOutboxDO event = event(3L, "DELETE");
        event.setUserId("stale-owner");
        when(itemMapper.selectById(10L)).thenReturn(item("ACTIVE", 2L));
        when(outboxMapper.completeEvent(eq("event-1"), eq("lease-1"), any())).thenReturn(1);

        worker.process(event);

        verify(projection).deleteVersion(
                "stale-owner", 10L, 3L, Duration.ofSeconds(10));
        verify(outboxMapper).completeEvent(eq("event-1"), eq("lease-1"), any());
        verify(outboxMapper, never()).rescheduleEvent(any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
    }

    @Test
    void staleVersionIsOnlyCleanedUpAndAcknowledged() {
        ChatMemoryOutboxDO event = event(1L, "UPSERT");
        when(itemMapper.selectById(10L)).thenReturn(item("ACTIVE", 2L));
        when(outboxMapper.completeEvent(eq("event-1"), eq("lease-1"), any())).thenReturn(1);

        worker.process(event);

        verify(projection).deleteVersion("user-1", 10L, 1L, Duration.ofSeconds(10));
        verify(projection, never()).upsert(any(), any());
        verify(outboxMapper).completeEvent(eq("event-1"), eq("lease-1"), any());
    }

    @Test
    void tombstoneOrMemoryWithoutEffectiveSourceDeletesProjection() {
        for (String status : List.of("DELETED", "QUARANTINED", "SUPERSEDED")) {
            ChatMemoryOutboxDO event = event(2L, "UPSERT");
            when(itemMapper.selectById(10L)).thenReturn(item(status, 2L));
            when(outboxMapper.completeEvent(eq("event-1"), eq("lease-1"), any())).thenReturn(1);
            worker.process(event);
        }

        ChatMemoryOutboxDO noSource = event(2L, "UPSERT");
        when(itemMapper.selectById(10L)).thenReturn(item("ACTIVE", 2L));
        when(sourceMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        worker.process(noSource);

        verify(projection, org.mockito.Mockito.times(4))
                .deleteMemory("user-1", 10L, Duration.ofSeconds(10));
        verify(projection, never()).upsert(any(), any());
    }

    @Test
    void futureOrExpiredActiveTruthDeletesProjectionInsteadOfRevivingIt() {
        ChatMemoryItemDO future = item("ACTIVE", 2L);
        future.setValidFrom(NOW_UTC.plusSeconds(1));
        ChatMemoryItemDO expired = item("ACTIVE", 2L);
        expired.setValidUntil(NOW_UTC);
        when(sourceMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(outboxMapper.completeEvent(eq("event-1"), eq("lease-1"), any())).thenReturn(1);

        when(itemMapper.selectById(10L)).thenReturn(future, expired);
        worker.process(event(2L, "UPSERT"));
        worker.process(event(2L, "UPSERT"));

        verify(projection, times(2))
                .deleteMemory("user-1", 10L, Duration.ofSeconds(10));
        verify(projection, never()).upsert(any(), any());
    }

    @Test
    void delayedDeleteReprojectsCurrentProjectableTruth() {
        when(itemMapper.selectById(10L)).thenReturn(item("ACTIVE", 2L));
        when(sourceMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(outboxMapper.completeEvent(eq("event-1"), eq("lease-1"), any())).thenReturn(1);

        worker.process(event(2L, "DELETE"));

        verify(projection).upsert(eq(new ChatMemoryProjection(
                "10:2", 10L, "user-1", 2L, "hash", "FACT", "content")),
                eq(Duration.ofSeconds(10)));
        verify(projection).deleteOtherVersions(
                "user-1", 10L, 2L, Duration.ofSeconds(10));
        verify(projection, never()).deleteMemory(any(), any(), any());
    }

    @Test
    void duplicateDeliveryReappliesImmutableProjectionAndAcknowledgesEachLease() {
        ChatMemoryOutboxDO first = event(2L, "UPSERT");
        ChatMemoryOutboxDO second = event(2L, "UPSERT");
        second.setLeaseToken("lease-2");
        when(itemMapper.selectById(10L)).thenReturn(item("ACTIVE", 2L));
        when(sourceMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(outboxMapper.completeEvent(eq("event-1"), any(), any())).thenReturn(1);

        worker.process(first);
        worker.process(second);

        verify(projection, times(2)).upsert(eq(new ChatMemoryProjection(
                "10:2", 10L, "user-1", 2L, "hash", "FACT", "content")),
                eq(Duration.ofSeconds(10)));
        verify(outboxMapper).completeEvent(eq("event-1"), eq("lease-1"), any());
        verify(outboxMapper).completeEvent(eq("event-1"), eq("lease-2"), any());
    }

    @Test
    void futureVersionAndOwnerMismatchAreRetriedWithoutWritingProjection() {
        ChatMemoryOutboxDO future = event(3L, "UPSERT");
        when(itemMapper.selectById(10L)).thenReturn(item("ACTIVE", 2L));
        when(outboxMapper.rescheduleEvent(eq("event-1"), eq("lease-1"), eq(1), any(),
                eq("MEMORY_PROJECTION_VERSION_AHEAD"), any())).thenReturn(1);

        worker.process(future);

        ChatMemoryOutboxDO mismatch = event(2L, "UPSERT");
        ChatMemoryItemDO foreign = item("ACTIVE", 2L);
        foreign.setUserId("user-2");
        when(itemMapper.selectById(10L)).thenReturn(foreign);
        when(outboxMapper.rescheduleEvent(eq("event-1"), eq("lease-1"), eq(1), any(),
                eq("MEMORY_PROJECTION_OWNER_MISMATCH"), any())).thenReturn(1);

        worker.process(mismatch);

        verify(projection, never()).upsert(any(), any());
        verify(projection, never()).deleteMemory(any(), any(), any());
        verify(outboxMapper).rescheduleEvent(eq("event-1"), eq("lease-1"), eq(1), any(),
                eq("MEMORY_PROJECTION_VERSION_AHEAD"), any());
        verify(outboxMapper).rescheduleEvent(eq("event-1"), eq("lease-1"), eq(1), any(),
                eq("MEMORY_PROJECTION_OWNER_MISMATCH"), any());
    }

    @Test
    void missingTruthWithoutDurableOwnerIsRetriedWithoutUnsafeDelete() {
        ChatMemoryOutboxDO event = event(2L, "DELETE");
        event.setUserId(" ");
        when(itemMapper.selectById(10L)).thenReturn(null);
        when(outboxMapper.rescheduleEvent(eq("event-1"), eq("lease-1"), eq(1), any(),
                eq("MEMORY_PROJECTION_USER_INVALID"), any())).thenReturn(1);

        worker.process(event);

        verify(projection, never()).deleteMemory(any(), any(), any());
        verify(outboxMapper).rescheduleEvent(eq("event-1"), eq("lease-1"), eq(1), any(),
                eq("MEMORY_PROJECTION_USER_INVALID"), any());
    }

    @Test
    void retryDelayIsCappedAtFiveMinutes() {
        properties.setWorkerPoll(Duration.ofMinutes(1));
        ChatMemoryOutboxDO event = event(2L, "UPSERT");
        event.setAttemptCount(8);
        when(itemMapper.selectById(10L)).thenReturn(item("ACTIVE", 2L));
        when(sourceMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        org.mockito.Mockito.doThrow(new ChatMemoryProjectionException("MEMORY_PROJECTION_TIMEOUT"))
                .when(projection).upsert(any(), any());
        org.mockito.ArgumentCaptor<LocalDateTime> nextAttempt =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.ArgumentCaptor<LocalDateTime> updatedAt =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        when(outboxMapper.rescheduleEvent(eq("event-1"), eq("lease-1"), eq(9),
                nextAttempt.capture(), eq("MEMORY_PROJECTION_TIMEOUT"), updatedAt.capture()))
                .thenReturn(1);

        worker.process(event);

        assertThat(java.time.Duration.between(updatedAt.getValue(), nextAttempt.getValue()))
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void projectionFailureUsesFencedRetryAndStopsAtConfiguredMaximum() {
        ChatMemoryOutboxDO event = event(2L, "UPSERT");
        event.setAttemptCount(8);
        when(itemMapper.selectById(10L)).thenReturn(item("ACTIVE", 2L));
        when(sourceMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        org.mockito.Mockito.doThrow(new ChatMemoryProjectionException("MEMORY_PROJECTION_TIMEOUT"))
                .when(projection).upsert(any(), any());
        when(outboxMapper.rescheduleEvent(eq("event-1"), eq("lease-1"), eq(9), any(),
                eq("MEMORY_PROJECTION_TIMEOUT"), any())).thenReturn(1);

        worker.process(event);

        verify(outboxMapper).rescheduleEvent(eq("event-1"), eq("lease-1"), eq(9), any(),
                eq("MEMORY_PROJECTION_TIMEOUT"), any());
        verify(outboxMapper, never()).completeEvent(any(), any(), any());
    }

    @Test
    void maximumAttemptMarksEventDeadWithLeaseFence() {
        ChatMemoryOutboxDO event = event(2L, "UPSERT");
        event.setAttemptCount(9);
        when(itemMapper.selectById(10L)).thenReturn(item("ACTIVE", 2L));
        when(sourceMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        org.mockito.Mockito.doThrow(new ChatMemoryProjectionException("MEMORY_PROJECTION_TIMEOUT"))
                .when(projection).upsert(any(), any());
        when(outboxMapper.markEventDead(eq("event-1"), eq("lease-1"), eq(10),
                eq("MEMORY_PROJECTION_TIMEOUT"), any())).thenReturn(1);

        worker.process(event);

        verify(outboxMapper).markEventDead(eq("event-1"), eq("lease-1"), eq(10),
                eq("MEMORY_PROJECTION_TIMEOUT"), any());
        verify(outboxMapper, never()).rescheduleEvent(any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
    }

    @Test
    void lostLeaseInOnePolledEventDoesNotBlockTheRemainingBatch() {
        ChatMemoryOutboxDO first = event(2L, "DELETE");
        ChatMemoryOutboxDO second = event(2L, "DELETE");
        second.setEventId("event-2");
        second.setLeaseToken("lease-2");
        when(claimService.claimAvailableEvents()).thenReturn(List.of(first, second));
        when(itemMapper.selectById(10L)).thenReturn(item("DELETED", 2L));
        when(outboxMapper.completeEvent(eq("event-1"), eq("lease-1"), any())).thenReturn(0);
        when(outboxMapper.completeEvent(eq("event-2"), eq("lease-2"), any())).thenReturn(1);

        assertThatCode(worker::poll).doesNotThrowAnyException();

        verify(outboxMapper).completeEvent(eq("event-2"), eq("lease-2"), any());
    }

    @Test
    void lostLeaseDuringAcknowledgementIsNeverReportedAsSuccess() {
        ChatMemoryOutboxDO event = event(2L, "DELETE");
        when(itemMapper.selectById(10L)).thenReturn(item("DELETED", 2L));
        when(outboxMapper.completeEvent(eq("event-1"), eq("lease-1"), any())).thenReturn(0);

        assertThatThrownBy(() -> worker.process(event))
                .isInstanceOf(ChatMemoryCommitException.class)
                .hasMessageContaining("MEMORY_LEASE_LOST");
    }

    private ChatMemoryOutboxDO event(long version, String operation) {
        ChatMemoryOutboxDO event = new ChatMemoryOutboxDO();
        event.setEventId("event-1");
        event.setMemoryId(10L);
        event.setUserId("user-1");
        event.setMemoryVersion(version);
        event.setOperation(operation);
        event.setStatus("PROCESSING");
        event.setAttemptCount(0);
        event.setLeaseToken("lease-1");
        return event;
    }

    private ChatMemoryItemDO item(String status, long version) {
        ChatMemoryItemDO item = new ChatMemoryItemDO();
        item.setId(10L);
        item.setUserId("user-1");
        item.setVersion(version);
        item.setStatus(status);
        item.setContentHash("hash");
        item.setCategory("FACT");
        item.setContent("content");
        item.setValidFrom(NOW_UTC.minusDays(1));
        item.setValidUntil(null);
        return item;
    }
}
