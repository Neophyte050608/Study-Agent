package io.github.imzmq.interview.chat.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.chat.application.contract.result.ChatMessageView;
import io.github.imzmq.interview.chat.application.contract.result.ChatSessionView;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatControllerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void keepsApiChatBasePath() {
        RequestMapping mapping = ChatController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/api/chat");
    }

    @Test
    void keepsLegacyRetrievalModeNormalizationWithoutExposingKnowledgeTypes() {
        assertThat(ChatController.normalizeRetrievalMode(null)).isNull();
        assertThat(ChatController.normalizeRetrievalMode("   ")).isNull();
        assertThat(ChatController.normalizeRetrievalMode("rag_only")).isEqualTo("RAG_ONLY");
        assertThat(ChatController.normalizeRetrievalMode(" local_graph_only ")).isEqualTo("LOCAL_GRAPH_ONLY");
        assertThat(ChatController.normalizeRetrievalMode("HYBRID_FUSION")).isEqualTo("HYBRID_FUSION");
        assertThat(ChatController.normalizeRetrievalMode("unsupported")).isNull();
    }

    @Test
    void sessionAndMessageViewsKeepLegacyJsonFieldNames() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
        ChatSessionView session = new ChatSessionView(1L, "s1", "u1", "标题", "摘要", 9L, false, now, now);
        ChatMessageView message = new ChatMessageView(2L, "m1", "s1", "assistant", "text", "内容",
                Map.of("traceId", "t1"), false, now);

        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(session)).fieldNames())
                .toIterable().containsExactly("id", "sessionId", "userId", "title", "contextSummary",
                        "summaryUpToMsgId", "deleted", "createdAt", "updatedAt");
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(message)).fieldNames())
                .toIterable().containsExactly("id", "messageId", "sessionId", "role", "contentType",
                        "content", "metadata", "deleted", "createdAt");
    }
}
