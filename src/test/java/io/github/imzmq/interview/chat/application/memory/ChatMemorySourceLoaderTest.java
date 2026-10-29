package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskDO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemorySourceLoaderTest {

    private final ChatMessageMapper mapper = mock(ChatMessageMapper.class);
    private final ChatMemorySourceLoader loader = new ChatMemorySourceLoader(mapper);

    @Test
    void loadsClaimedSourcePlusAtMostFourPriorMessagesInChronologicalOrder() {
        ChatMemoryExtractionTaskDO task = task();
        when(mapper.selectMemorySourceMessages("user-1", "session-1", List.of("m-10")))
                .thenReturn(List.of(message(10L, "m-10", "user")));
        when(mapper.selectMemoryPriorContext("user-1", "session-1", 10L, 4))
                .thenReturn(List.of(message(9L, "m-9", "assistant")));

        List<ChatMemorySourceMessage> result = loader.loadBatch(List.of(task));

        assertThat(result).extracting(ChatMemorySourceMessage::messageId)
                .containsExactly("m-9", "m-10");
        assertThat(result).extracting(ChatMemorySourceMessage::createdAt)
                .containsExactly(
                        LocalDateTime.of(2026, 10, 29, 9, 0),
                        LocalDateTime.of(2026, 10, 29, 10, 0));
        verify(mapper).selectMemoryPriorContext("user-1", "session-1", 10L, 4);
    }

    @Test
    void rejectsMissingClaimedUserSource() {
        when(mapper.selectMemorySourceMessages("user-1", "session-1", List.of("m-10")))
                .thenReturn(List.of());

        assertThatThrownBy(() -> loader.loadBatch(List.of(task())))
                .isInstanceOf(ChatMemoryExtractionException.class)
                .extracting("code")
                .isEqualTo("MEMORY_SOURCE_INVALID");
    }

    private ChatMemoryExtractionTaskDO task() {
        ChatMemoryExtractionTaskDO task = new ChatMemoryExtractionTaskDO();
        task.setUserId("user-1");
        task.setSessionId("session-1");
        task.setSourceMessageId("m-10");
        task.setSourceMessagePk(10L);
        return task;
    }

    private ChatMessageDO message(long id, String messageId, String role) {
        ChatMessageDO message = new ChatMessageDO();
        message.setId(id);
        message.setMessageId(messageId);
        message.setSessionId("session-1");
        message.setRole(role);
        message.setContent("content-" + id);
        message.setDeleted(false);
        message.setCreatedAt(LocalDateTime.of(2026, 10, 29, id == 10 ? 10 : 9, 0));
        return message;
    }
}
