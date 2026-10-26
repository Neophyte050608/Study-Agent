package io.github.imzmq.interview.chat.application.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import io.github.imzmq.interview.chat.infrastructure.milvus.ChatMemoryProjectionCursor;
import io.github.imzmq.interview.chat.infrastructure.milvus.ChatMemoryProjectionPort;
import io.github.imzmq.interview.chat.infrastructure.milvus.ChatMemoryProjectionRow;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryOutboxDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryOutboxMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryRepairServiceTest {

    @Test
    void twoSidedSweepRepairsDriftOnlyThroughExactOutboxKeys() {
        ChatMemoryItemMapper itemMapper = mock(ChatMemoryItemMapper.class);
        ChatMemoryOutboxMapper outboxMapper = mock(ChatMemoryOutboxMapper.class);
        ChatMemoryProjectionPort projectionPort = mock(ChatMemoryProjectionPort.class);
        ChatMemorySourceMapper sourceMapper = mock(ChatMemorySourceMapper.class);
        ChatMemoryProperties properties = properties();
        ChatMemoryOutboxEventService outboxService = new ChatMemoryOutboxEventService(outboxMapper);
        ChatMemoryRepairService service = new ChatMemoryRepairService(
                itemMapper, sourceMapper, projectionPort, outboxService, properties,
                mock(ChatMemoryObservationPublisher.class));

        List<ChatMemoryItemDO> truth = List.of(
                item(1L, "ACTIVE", 2L, "hash-1", "FACT"),
                item(2L, "ACTIVE", 2L, "hash-2", "PREFERENCE"),
                item(3L, "ACTIVE", 1L, "hash-3", "CONSTRAINT"),
                item(5L, "DELETED", 3L, "hash-5", "FACT"),
                item(6L, "ACTIVE", 1L, "hash-6", "FACT"));
        when(itemMapper.selectRepairPage(0L, 2)).thenReturn(truth.subList(0, 2));
        when(itemMapper.selectRepairPage(2L, 2)).thenReturn(truth.subList(2, 4));
        when(itemMapper.selectRepairPage(5L, 2)).thenReturn(truth.subList(4, 5));
        when(itemMapper.selectRepairPage(6L, 2)).thenReturn(List.of());

        List<ChatMemoryProjectionRow> rows = List.of(
                row("2:1", 2L, 1L, "hash-2", "PREFERENCE"),
                row("3:1", 3L, 1L, "wrong-hash", "CONSTRAINT"),
                row("4:1", 4L, 1L, "hash-4", "FACT"),
                row("5:3", 5L, 3L, "hash-5", "FACT"),
                row("6:1", 6L, 1L, "hash-6", "FACT"));
        when(projectionPort.findByProjectionIds(anyList(), eq(Duration.ofSeconds(3))))
                .thenAnswer(invocation -> {
                    List<String> ids = invocation.getArgument(0);
                    return rows.stream().filter(row -> ids.contains(row.projectionId())).toList();
                });
        when(projectionPort.openCursor(2, Duration.ofSeconds(3)))
                .thenReturn(cursor(rows.subList(0, 2), rows.subList(2, 4), rows.subList(4, 5)));
        when(itemMapper.selectBatchIds(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return truth.stream().filter(item -> ids.contains(item.getId())).toList();
        });
        when(sourceMapper.selectEffectiveMemoryIds(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return truth.stream()
                    .map(ChatMemoryItemDO::getId)
                    .filter(ids::contains)
                    .toList();
        });

        ChatMemoryOutboxDO doneMissing = event("existing-missing", 1L, 2L, "UPSERT", "DONE");
        ChatMemoryOutboxDO deadOld = event("existing-old", 2L, 1L, "DELETE", "DEAD");
        when(outboxMapper.selectOne(any(Wrapper.class)))
                .thenReturn(doneMissing, null, null, null, deadOld, null);

        service.repairAll();

        assertThat(doneMissing.getEventId()).isEqualTo("existing-missing");
        assertThat(doneMissing.getStatus()).isEqualTo("PENDING");
        assertThat(doneMissing.getAttemptCount()).isZero();
        assertThat(doneMissing.getLeaseToken()).isNull();
        assertThat(doneMissing.getErrorCode()).isNull();
        assertThat(deadOld.getEventId()).isEqualTo("existing-old");
        assertThat(deadOld.getStatus()).isEqualTo("PENDING");

        verify(outboxMapper).updateById(doneMissing);
        verify(outboxMapper).updateById(deadOld);
        org.mockito.ArgumentCaptor<ChatMemoryOutboxDO> inserted =
                org.mockito.ArgumentCaptor.forClass(ChatMemoryOutboxDO.class);
        verify(outboxMapper, times(4)).insert(inserted.capture());
        assertThat(inserted.getAllValues())
                .extracting(event -> event.getMemoryId() + ":" + event.getMemoryVersion()
                        + ":" + event.getOperation())
                .containsExactlyInAnyOrder(
                        "2:2:UPSERT", "3:1:UPSERT", "4:1:DELETE", "5:3:DELETE");
        verify(projectionPort, never()).upsert(any(), any());
        verify(projectionPort, never()).deleteMemory(any(), any(), any());
        verify(projectionPort, never()).deleteVersion(any(), any(), any(), any());
        verify(projectionPort, never()).deleteOtherVersions(any(), any(), any(), any());
    }

    @Test
    void currentImmutableProjectionWithWrongOwnerQueuesOnlyUpsert() {
        ChatMemoryItemMapper itemMapper = mock(ChatMemoryItemMapper.class);
        ChatMemorySourceMapper sourceMapper = mock(ChatMemorySourceMapper.class);
        ChatMemoryOutboxMapper outboxMapper = mock(ChatMemoryOutboxMapper.class);
        ChatMemoryProjectionPort projectionPort = mock(ChatMemoryProjectionPort.class);
        ChatMemoryRepairService service = new ChatMemoryRepairService(
                itemMapper, sourceMapper, projectionPort,
                new ChatMemoryOutboxEventService(outboxMapper), properties(),
                mock(ChatMemoryObservationPublisher.class));
        ChatMemoryItemDO item = item(10L, "ACTIVE", 2L, "hash", "FACT");
        ChatMemoryProjectionRow row = new ChatMemoryProjectionRow(
                "10:2", 10L, "wrong-owner", 2L, "hash", "FACT");
        when(itemMapper.selectRepairPage(0L, 2)).thenReturn(List.of(item));
        when(projectionPort.findByProjectionIds(
                List.of("10:2"), Duration.ofSeconds(3))).thenReturn(List.of(row));
        when(projectionPort.openCursor(2, Duration.ofSeconds(3)))
                .thenReturn(cursor(List.of(row)));
        when(itemMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(item));
        when(sourceMapper.selectEffectiveMemoryIds(anyList())).thenReturn(List.of(10L));
        when(outboxMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        service.repairAll();

        org.mockito.ArgumentCaptor<ChatMemoryOutboxDO> inserted =
                org.mockito.ArgumentCaptor.forClass(ChatMemoryOutboxDO.class);
        verify(outboxMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getOperation()).isEqualTo("UPSERT");
        assertThat(inserted.getValue().getUserId()).isEqualTo("user-1");
    }

    @Test
    void activeMemoryWithoutAnEffectiveSourceQueuesProjectionDeletion() {
        ChatMemoryItemMapper itemMapper = mock(ChatMemoryItemMapper.class);
        ChatMemorySourceMapper sourceMapper = mock(ChatMemorySourceMapper.class);
        ChatMemoryOutboxMapper outboxMapper = mock(ChatMemoryOutboxMapper.class);
        ChatMemoryProjectionPort projectionPort = mock(ChatMemoryProjectionPort.class);
        ChatMemoryRepairService service = new ChatMemoryRepairService(
                itemMapper, sourceMapper, projectionPort,
                new ChatMemoryOutboxEventService(outboxMapper), properties(),
                mock(ChatMemoryObservationPublisher.class));
        ChatMemoryItemDO item = item(10L, "ACTIVE", 2L, "hash", "FACT");
        ChatMemoryProjectionRow row = row("10:2", 10L, 2L, "hash", "FACT");
        when(itemMapper.selectRepairPage(0L, 2)).thenReturn(List.of(item));
        when(projectionPort.findByProjectionIds(
                List.of("10:2"), Duration.ofSeconds(3))).thenReturn(List.of(row));
        when(projectionPort.openCursor(2, Duration.ofSeconds(3)))
                .thenReturn(cursor(List.of(row)));
        when(itemMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(item));
        when(sourceMapper.selectEffectiveMemoryIds(anyList())).thenReturn(List.of());
        when(outboxMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        service.repairAll();

        org.mockito.ArgumentCaptor<ChatMemoryOutboxDO> inserted =
                org.mockito.ArgumentCaptor.forClass(ChatMemoryOutboxDO.class);
        verify(outboxMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getOperation()).isEqualTo("DELETE");
        assertThat(inserted.getValue().getMemoryId()).isEqualTo(10L);
        assertThat(inserted.getValue().getMemoryVersion()).isEqualTo(2L);
    }

    @Test
    void schedulerSkipsAnOverlappingSweep() throws Exception {
        ChatMemoryRepairService service = mock(ChatMemoryRepairService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(service.repairAll()).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new ChatMemoryRepairService.RepairSummary(0, 0, 0);
        });
        ChatMemoryRepairScheduler scheduler = new ChatMemoryRepairScheduler(service);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(scheduler::repair);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            scheduler.repair();
            release.countDown();
        } finally {
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        verify(service, times(1)).repairAll();
    }

    private ChatMemoryProperties properties() {
        ChatMemoryProperties properties = new ChatMemoryProperties();
        properties.setRepairPageSize(2);
        properties.setRpcTimeout(Duration.ofSeconds(3));
        return properties;
    }

    private ChatMemoryProjectionCursor cursor(List<ChatMemoryProjectionRow>... pages) {
        List<List<ChatMemoryProjectionRow>> remaining = new ArrayList<>(List.of(pages));
        return new ChatMemoryProjectionCursor() {
            @Override
            public List<ChatMemoryProjectionRow> nextPage() {
                return remaining.isEmpty() ? List.of() : remaining.removeFirst();
            }

            @Override
            public void close() {
            }
        };
    }

    private ChatMemoryItemDO item(
            long id, String status, long version, String hash, String category) {
        ChatMemoryItemDO item = new ChatMemoryItemDO();
        item.setId(id);
        item.setUserId("user-1");
        item.setStatus(status);
        item.setVersion(version);
        item.setContentHash(hash);
        item.setCategory(category);
        return item;
    }

    private ChatMemoryProjectionRow row(
            String projectionId, long memoryId, long version, String hash, String category) {
        return new ChatMemoryProjectionRow(
                projectionId, memoryId, "user-1", version, hash, category);
    }

    private ChatMemoryOutboxDO event(
            String eventId, long memoryId, long version, String operation, String status) {
        ChatMemoryOutboxDO event = new ChatMemoryOutboxDO();
        event.setEventId(eventId);
        event.setMemoryId(memoryId);
        event.setUserId("user-1");
        event.setMemoryVersion(version);
        event.setTarget("MILVUS");
        event.setOperation(operation);
        event.setStatus(status);
        event.setAttemptCount(9);
        event.setLeaseToken("old-lease");
        event.setLeaseUntil(LocalDateTime.now().plusMinutes(1));
        event.setErrorCode("old-error");
        return event;
    }
}
