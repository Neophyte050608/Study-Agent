package io.github.imzmq.interview.chat.application.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import io.github.imzmq.interview.chat.application.ChatSessionAccessException;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatMemoryMessageTransactionServiceTest {

    private final ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
    private final ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
    private final ChatMemoryExtractionTaskMapper taskMapper = mock(ChatMemoryExtractionTaskMapper.class);
    private final ChatMemoryProperties properties = new ChatMemoryProperties();
    private final ChatMemoryMessageTransactionService service = new ChatMemoryMessageTransactionService(
            sessionMapper, messageMapper, taskMapper, properties);

    @Test
    void enabledSavePersistsMessageAndOneTask() {
        properties.setEnabled(true);
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(ownedSession());
        doAnswer(invocation -> {
            ChatMessageDO message = invocation.getArgument(0);
            message.setId(41L);
            return 1;
        }).when(messageMapper).insert(any(ChatMessageDO.class));

        var result = service.saveUserMessage("user-a", "session-a", "I prefer Java");

        assertThat(result.id()).isEqualTo(41L);
        assertThat(result.messageId()).isNotBlank();
        verify(messageMapper).insert(argThat(message ->
                "session-a".equals(message.getSessionId())
                        && "user".equals(message.getRole())
                        && "I prefer Java".equals(message.getContent())));
        verify(taskMapper).insert(argThat(task ->
                "user-a".equals(task.getUserId())
                        && "session-a".equals(task.getSessionId())
                        && result.messageId().equals(task.getSourceMessageId())
                        && Long.valueOf(41L).equals(task.getSourceMessagePk())
                        && "PENDING".equals(task.getStatus())
                        && task.getNextAttemptAt() != null));
        verify(sessionMapper).update(any(), any());
    }

    @Test
    void disabledSavePersistsMessageWithoutTask() {
        properties.setEnabled(false);
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(ownedSession());

        service.saveUserMessage("user-a", "session-a", "hello");

        verify(messageMapper).insert(any(ChatMessageDO.class));
        verifyNoInteractions(taskMapper);
    }

    @Test
    void missingOrForeignSessionDoesNotWriteAnything() {
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.saveUserMessage("user-a", "session-b", "hello"))
                .isInstanceOf(ChatSessionAccessException.class)
                .hasMessage("会话不存在");

        verify(messageMapper, never()).insert(any());
        verify(sessionMapper, never()).update(any(), any());
        verifyNoInteractions(taskMapper);
    }

    @Test
    void saveBoundaryIsTransactional() throws Exception {
        Method method = ChatMemoryMessageTransactionService.class.getMethod(
                "saveUserMessage", String.class, String.class, String.class);

        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }

    private ChatSessionDO ownedSession() {
        ChatSessionDO session = new ChatSessionDO();
        session.setId(7L);
        session.setSessionId("session-a");
        session.setUserId("user-a");
        session.setDeleted(false);
        return session;
    }
}
