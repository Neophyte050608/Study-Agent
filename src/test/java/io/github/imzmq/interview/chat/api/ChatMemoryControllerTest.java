package io.github.imzmq.interview.chat.api;

import io.github.imzmq.interview.chat.application.contract.ChatMemoryOperations;
import io.github.imzmq.interview.chat.application.contract.result.ChatMemoryRelationView;
import io.github.imzmq.interview.chat.application.contract.result.ChatMemoryView;
import io.github.imzmq.interview.chat.application.memory.ChatMemoryManagementException;
import io.github.imzmq.interview.identity.application.contract.UserIdentityQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatMemoryControllerTest {

    private final ChatMemoryOperations operations = mock(ChatMemoryOperations.class);
    private final UserIdentityQuery identityQuery = mock(UserIdentityQuery.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ChatMemoryController(operations, identityQuery))
                .setControllerAdvice(new ChatMemoryApiExceptionHandler())
                .build();
        when(identityQuery.resolveTrusted(eq("alice"), any())).thenReturn("subject:alice");
    }

    @Test
    void listsLifecycleFieldsForTrustedPrincipalAndIgnoresForgedHeader() throws Exception {
        Instant now = Instant.parse("2026-10-29T10:00:00Z");
        when(operations.list("subject:alice", "QUARANTINED", 20, 99L)).thenReturn(List.of(
                new ChatMemoryView(7L, "FACT", "fact.current_city", "MULTI", "杭州",
                        "QUARANTINED", "AMBIGUOUS_BATCH_ORDER", 2L,
                        now.minusSeconds(60), null, now.minusSeconds(120), now,
                        List.of("message-1"))));

        mockMvc.perform(get("/api/chat/memories")
                        .principal(() -> "alice")
                        .param("status", "QUARANTINED")
                        .param("limit", "20")
                        .param("beforeId", "99")
                        .header("X-User-Id", "forged"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].subjectKey").value("fact.current_city"))
                .andExpect(jsonPath("$[0].subjectCardinality").value("MULTI"))
                .andExpect(jsonPath("$[0].status").value("QUARANTINED"))
                .andExpect(jsonPath("$[0].quarantineReason").value("AMBIGUOUS_BATCH_ORDER"))
                .andExpect(jsonPath("$[0].validFrom").exists())
                .andExpect(jsonPath("$[0].sourceMessageIds[0]").value("message-1"))
                .andExpect(jsonPath("$[0].contentHash").doesNotExist())
                .andExpect(jsonPath("$[0].embedding").doesNotExist());

        verify(operations).list("subject:alice", "QUARANTINED", 20, 99L);
        verify(identityQuery, never()).resolve(any(), any(), any());
    }

    @Test
    void returnsOwnerScopedEvolutionRelations() throws Exception {
        Instant now = Instant.parse("2026-10-29T10:00:00Z");
        when(operations.evolution("subject:alice", 7L)).thenReturn(List.of(
                new ChatMemoryRelationView(8L, 7L, "CONFLICTS_WITH", now)));

        mockMvc.perform(get("/api/chat/memories/7/evolution")
                        .principal(() -> "alice")
                        .header("X-User-Id", "forged"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fromMemoryId").value(8))
                .andExpect(jsonPath("$[0].toMemoryId").value(7))
                .andExpect(jsonPath("$[0].relationType").value("CONFLICTS_WITH"))
                .andExpect(jsonPath("$[0].createdAt").exists());

        verify(operations).evolution("subject:alice", 7L);
    }

    @Test
    void resolvesReplacementAndCoexistenceWithRequiredVersionFence() throws Exception {
        mockMvc.perform(post("/api/chat/memories/7/resolve/replacement")
                        .principal(() -> "alice")
                        .param("expectedVersion", "3"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/chat/memories/8/resolve/coexistence")
                        .principal(() -> "alice")
                        .param("expectedVersion", "4"))
                .andExpect(status().isOk());

        verify(operations).confirmReplacement("subject:alice", 7L, 3L);
        verify(operations).confirmCoexistence("subject:alice", 8L, 4L);
    }

    @Test
    void deletesThroughCurrentUserContractWithRequiredVersionFence() throws Exception {
        mockMvc.perform(delete("/api/chat/memories/7")
                        .principal(() -> "alice")
                        .param("expectedVersion", "3"))
                .andExpect(status().isOk());

        verify(operations).delete("subject:alice", 7L, 3L);
    }

    @Test
    void mapsStableManagementErrorsWithoutDisclosingInternals() throws Exception {
        doThrow(new ChatMemoryManagementException("MEMORY_NOT_FOUND"))
                .when(operations).delete("subject:alice", 7L, 3L);
        mockMvc.perform(delete("/api/chat/memories/7")
                        .principal(() -> "alice")
                        .param("expectedVersion", "3"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMORY_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("记忆不存在"));

        doThrow(new ChatMemoryManagementException("MEMORY_STATE_CONFLICT"))
                .when(operations).confirmReplacement("subject:alice", 8L, 4L);
        mockMvc.perform(post("/api/chat/memories/8/resolve/replacement")
                        .principal(() -> "alice")
                        .param("expectedVersion", "4"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMORY_STATE_CONFLICT"));

        doThrow(new ChatMemoryManagementException("MEMORY_CARDINALITY_CONFLICT"))
                .when(operations).confirmCoexistence("subject:alice", 9L, 5L);
        mockMvc.perform(post("/api/chat/memories/9/resolve/coexistence")
                        .principal(() -> "alice")
                        .param("expectedVersion", "5"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMORY_CARDINALITY_CONFLICT"));
    }

    @Test
    void mapsInvalidStatusAndUnexpectedErrorsWithoutLeakingExceptionText() throws Exception {
        when(operations.list("subject:alice", "active", 20, null))
                .thenThrow(new ChatMemoryManagementException("MEMORY_STATUS_INVALID"));
        mockMvc.perform(get("/api/chat/memories")
                        .principal(() -> "alice")
                        .param("status", "active"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMORY_STATUS_INVALID"));

        when(operations.evolution("subject:alice", 7L))
                .thenThrow(new IllegalStateException("database-password-secret"));
        mockMvc.perform(get("/api/chat/memories/7/evolution")
                        .principal(() -> "alice"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("database-password-secret"))))
                .andExpect(jsonPath("$.code").value("MEMORY_INTERNAL_ERROR"));
    }

    @Test
    void rejectsWriteWithoutExpectedVersion() throws Exception {
        mockMvc.perform(delete("/api/chat/memories/7")
                        .principal(() -> "alice"))
                .andExpect(status().isBadRequest());

        verify(operations, never()).delete(any(), any(Long.class), any(Long.class));
    }
}
