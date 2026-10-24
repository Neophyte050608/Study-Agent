package io.github.imzmq.interview.chat.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.imzmq.interview.agent.runtime.TaskRouterAgent;
import io.github.imzmq.interview.chat.application.contract.result.ChatMessageView;
import io.github.imzmq.interview.chat.application.contract.result.ChatSessionView;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebChatServiceTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "chat-test");
        TableInfoHelper.initTableInfo(assistant, ChatMessageDO.class);
        TableInfoHelper.initTableInfo(assistant, ChatSessionDO.class);
    }

    private final ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
    private final ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
    private final ChatContextCompressor contextCompressor = mock(ChatContextCompressor.class);
    private final TaskRouterAgent taskRouterAgent = mock(TaskRouterAgent.class);
    private final io.github.imzmq.interview.chat.application.contract.TaskResponsePresentationOperations presentationService = mock(io.github.imzmq.interview.chat.application.contract.TaskResponsePresentationOperations.class);
    private final WebChatService service = new WebChatService(
            sessionMapper, messageMapper, taskRouterAgent, contextCompressor, presentationService);

    @Test
    void maps_every_session_persistence_field_to_public_result() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 23, 20, 0);
        LocalDateTime updatedAt = createdAt.plusMinutes(5);
        ChatSessionDO row = new ChatSessionDO();
        row.setId(7L);
        row.setSessionId("session-1");
        row.setUserId("user-1");
        row.setTitle("title");
        row.setContextSummary("summary");
        row.setSummaryUpToMsgId(6L);
        row.setDeleted(false);
        row.setCreatedAt(createdAt);
        row.setUpdatedAt(updatedAt);
        when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));

        List<ChatSessionView> result = service.listSessions("user-1");

        assertThat(result).containsExactly(new ChatSessionView(
                7L, "session-1", "user-1", "title", "summary", 6L,
                false, createdAt, updatedAt));
    }

    @Test
    void maps_every_message_persistence_field_to_public_result() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 23, 20, 0);
        ChatMessageDO row = new ChatMessageDO();
        row.setId(9L);
        row.setMessageId("message-1");
        row.setSessionId("session-1");
        row.setRole("assistant");
        row.setContentType("text");
        row.setContent("answer");
        row.setMetadata(Map.of("traceId", "trace-1"));
        row.setDeleted(false);
        row.setCreatedAt(createdAt);
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));

        List<ChatMessageView> result = service.listMessages("session-1", 500, 99L);

        assertThat(result).containsExactly(new ChatMessageView(
                9L, "message-1", "session-1", "assistant", "text", "answer",
                Map.of("traceId", "trace-1"), false, createdAt));
    }

    @Test
    void caps_message_page_size_at_two_hundred() {
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.listMessages("session-1", 500, null);

        verify(messageMapper).selectList(argThat(wrapper -> wrapper.getTargetSql().contains("LIMIT 200")));
    }

    @Test
    void updates_existing_assistant_placeholder_and_preserves_requested_content_type() {
        ChatMessageDO existing = new ChatMessageDO();
        existing.setId(9L);
        existing.setMessageId("message-1");
        existing.setSessionId("session-1");
        existing.setContentType("text");
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        service.updateAssistantMessage("message-1", "最终答案", Map.of("generationStatus", "COMPLETED"), "rich");

        verify(messageMapper).updateById(argThat(message ->
                "最终答案".equals(message.getContent())
                        && "rich".equals(message.getContentType())
                        && "COMPLETED".equals(message.getMetadata().get("generationStatus"))));
        verify(sessionMapper).update(any(), any());
    }

    @Test
    void returns_active_business_session_id_from_recent_message_metadata() {
        ChatMessageDO newest = new ChatMessageDO();
        newest.setMetadata(Map.of("other", "value"));
        ChatMessageDO previous = new ChatMessageDO();
        previous.setMetadata(Map.of("interviewSessionId", "interview-7", "codingSessionId", "coding-8"));
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(newest, previous));

        assertThat(service.findActiveInterviewSessionId("chat-1")).isEqualTo("interview-7");
        assertThat(service.findActiveCodingSessionId("chat-1")).isEqualTo("coding-8");
    }
}
