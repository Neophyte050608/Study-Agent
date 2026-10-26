package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.application.contract.result.ChatMemoryView;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryManagementServiceTest {

    private final ChatMemoryItemMapper itemMapper = mock(ChatMemoryItemMapper.class);
    private final ChatMemorySourceMapper sourceMapper = mock(ChatMemorySourceMapper.class);
    private final ChatMemoryOutboxEventService outboxEventService = mock(ChatMemoryOutboxEventService.class);
    private final ChatMemoryManagementService service = new ChatMemoryManagementService(
            itemMapper, sourceMapper, outboxEventService);

    @Test
    void listsOnlyCurrentUserRowsWithClampedPagingAndAccessibleSources() {
        ChatMemoryItemDO row = item(7L, "user-a", "ACTIVE", "FACT", "正在学习 Java", 3L);
        when(itemMapper.selectManagementPage("user-a", 99L, 100)).thenReturn(List.of(row));
        ChatMemorySourceDO source = source(7L, "user-a", "message-1");
        when(sourceMapper.selectAccessibleSources("user-a", List.of(7L))).thenReturn(List.of(source));

        List<ChatMemoryView> result = service.list("user-a", 500, 99L);

        assertThat(result).containsExactly(new ChatMemoryView(
                7L,
                "FACT",
                "正在学习 Java",
                "ACTIVE",
                3L,
                row.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant(),
                row.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant(),
                List.of("message-1")));
        verify(itemMapper).selectManagementPage("user-a", 99L, 100);
    }

    @Test
    void clampsSmallListLimitToOne() {
        when(itemMapper.selectManagementPage("user-a", null, 1)).thenReturn(List.of());

        assertThat(service.list("user-a", 0, null)).isEmpty();

        verify(itemMapper).selectManagementPage("user-a", null, 1);
    }

    @Test
    void explicitDeleteClearsContentInvalidatesSourcesAndQueuesDelete() {
        ChatMemoryItemDO row = item(7L, "user-a", "ACTIVE", "FACT", "private", 3L);
        when(itemMapper.lockOwnedItem("user-a", 7L)).thenReturn(row);
        when(itemMapper.markDeleted(7L, "user-a", 3L, 4L)).thenReturn(1);

        service.delete("user-a", 7L);

        verify(sourceMapper).invalidateMemorySources(7L, "user-a");
        verify(itemMapper).markDeleted(7L, "user-a", 3L, 4L);
        verify(outboxEventService).enqueue(7L, "user-a", 4L, "DELETE");
    }

    @Test
    void explicitDeleteAlsoRemovesOwnedQuarantinedMemory() {
        ChatMemoryItemDO row = item(10L, "user-a", "QUARANTINED", "FACT", "private", 6L);
        when(itemMapper.lockOwnedItem("user-a", 10L)).thenReturn(row);
        when(itemMapper.markDeleted(10L, "user-a", 6L, 7L)).thenReturn(1);

        service.delete("user-a", 10L);

        verify(sourceMapper).invalidateMemorySources(10L, "user-a");
        verify(outboxEventService).enqueue(10L, "user-a", 7L, "DELETE");
    }

    @Test
    void deleteIsIdempotentAndDoesNotDiscloseForeignOrMissingRows() {
        when(itemMapper.lockOwnedItem("user-a", 8L)).thenReturn(null);
        when(itemMapper.lockOwnedItem("user-a", 9L))
                .thenReturn(item(9L, "user-a", "DELETED", "FACT", "", 2L));

        service.delete("user-a", 8L);
        service.delete("user-a", 9L);

        verify(sourceMapper, never()).invalidateMemorySources(8L, "user-a");
        verify(sourceMapper, never()).invalidateMemorySources(9L, "user-a");
        verify(outboxEventService, never()).enqueue(8L, "user-a", 1L, "DELETE");
        verify(outboxEventService, never()).enqueue(9L, "user-a", 3L, "DELETE");
    }

    private ChatMemoryItemDO item(
            long id, String userId, String status, String category, String content, long version) {
        ChatMemoryItemDO item = new ChatMemoryItemDO();
        item.setId(id);
        item.setUserId(userId);
        item.setStatus(status);
        item.setCategory(category);
        item.setContent(content);
        item.setContentHash("hidden-hash");
        item.setVersion(version);
        item.setCreatedAt(LocalDateTime.of(2026, 10, 26, 10, 0));
        item.setUpdatedAt(LocalDateTime.of(2026, 10, 26, 11, 0));
        return item;
    }

    private ChatMemorySourceDO source(long memoryId, String userId, String messageId) {
        ChatMemorySourceDO source = new ChatMemorySourceDO();
        source.setMemoryId(memoryId);
        source.setUserId(userId);
        source.setSourceMessageId(messageId);
        return source;
    }
}
