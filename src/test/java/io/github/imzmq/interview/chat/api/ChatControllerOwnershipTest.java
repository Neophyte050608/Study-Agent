package io.github.imzmq.interview.chat.api;

import io.github.imzmq.interview.chat.application.ChatSessionAccessException;
import io.github.imzmq.interview.chat.application.ChatStreamingService;
import io.github.imzmq.interview.chat.application.contract.ChatMessageOperations;
import io.github.imzmq.interview.chat.application.contract.ChatSessionOperations;
import io.github.imzmq.interview.identity.application.contract.UserIdentityQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerOwnershipTest {

    private final ChatSessionOperations chatSessionOperations = mock(ChatSessionOperations.class);
    private final ChatMessageOperations chatMessageOperations = mock(ChatMessageOperations.class);
    private final ChatStreamingService chatStreamingService = mock(ChatStreamingService.class);
    private final UserIdentityQuery userIdentityQuery = mock(UserIdentityQuery.class);
    private final ChatController controller = new ChatController(
            chatSessionOperations, chatMessageOperations, chatStreamingService, userIdentityQuery);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ChatApiExceptionHandler())
                .build();
        when(userIdentityQuery.resolveTrusted(eq("alice"), any())).thenReturn("subject:alice");
    }

    @Test
    void renameUsesVerifiedPrincipalAndIgnoresIdentityHeaders() throws Exception {
        mockMvc.perform(put("/api/chat/sessions/session-b")
                        .principal(() -> "alice")
                        .header("Authorization", "Bearer forged")
                        .header("X-User-Id", "forged")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"new\"}"))
                .andExpect(status().isOk());

        verify(chatSessionOperations).renameSession("subject:alice", "session-b", "new");
        verify(userIdentityQuery).resolveTrusted(eq("alice"), any());
        verify(userIdentityQuery, never()).resolve(any(), any(), any());
    }

    @Test
    void deleteUsesVerifiedPrincipal() throws Exception {
        mockMvc.perform(delete("/api/chat/sessions/session-b")
                        .principal(() -> "alice"))
                .andExpect(status().isOk());

        verify(chatSessionOperations).deleteSession("subject:alice", "session-b");
    }

    @Test
    void listMessagesUsesVerifiedPrincipal() throws Exception {
        when(chatMessageOperations.listMessages("subject:alice", "session-b", 50, null))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/chat/sessions/session-b/messages")
                        .principal(() -> "alice"))
                .andExpect(status().isOk());

        verify(chatMessageOperations).listMessages("subject:alice", "session-b", 50, null);
    }

    @Test
    void streamUsesVerifiedPrincipal() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal((Principal) () -> "alice");
        request.addHeader("Authorization", "Bearer forged");
        request.addHeader("X-User-Id", "forged");
        SseEmitter emitter = new SseEmitter();
        when(chatStreamingService.streamChat("session-b", "subject:alice", "question", "RAG_ONLY"))
                .thenReturn(emitter);

        assertThat(controller.streamChat(
                "session-b",
                Map.of("content", "question", "retrievalMode", "rag_only"),
                request
        )).isSameAs(emitter);

        verify(chatStreamingService).streamChat(
                "session-b", "subject:alice", "question", "RAG_ONLY");
        verify(userIdentityQuery, never()).resolve(any(), any(), any());
    }

    @Test
    void sessionAccessFailureReturnsNonDisclosingNotFoundResponse() throws Exception {
        when(chatSessionOperations.renameSession("subject:alice", "missing", "new"))
                .thenThrow(new ChatSessionAccessException());

        mockMvc.perform(put("/api/chat/sessions/missing")
                        .principal(() -> "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"new\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_SESSION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("会话不存在"));
    }
}
