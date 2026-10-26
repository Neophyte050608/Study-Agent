package io.github.imzmq.interview.chat.api;

import io.github.imzmq.interview.chat.application.contract.ChatMemoryOperations;
import io.github.imzmq.interview.chat.application.contract.result.ChatMemoryView;
import io.github.imzmq.interview.identity.application.contract.UserIdentityQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                .build();
        when(identityQuery.resolveTrusted(eq("alice"), any())).thenReturn("subject:alice");
    }

    @Test
    void listsSdkNeutralMemoryResponsesForVerifiedPrincipal() throws Exception {
        Instant now = Instant.parse("2026-10-26T10:00:00Z");
        when(operations.list("subject:alice", 20, 99L)).thenReturn(List.of(
                new ChatMemoryView(7L, "FACT", "Java", "ACTIVE", 2L,
                        now, now, List.of("message-1"))));

        mockMvc.perform(get("/api/chat/memories")
                        .principal(() -> "alice")
                        .param("limit", "20")
                        .param("beforeId", "99")
                        .header("X-User-Id", "forged"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].category").value("FACT"))
                .andExpect(jsonPath("$[0].content").value("Java"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].sourceMessageIds[0]").value("message-1"))
                .andExpect(jsonPath("$[0].contentHash").doesNotExist())
                .andExpect(jsonPath("$[0].embedding").doesNotExist());

        verify(operations).list("subject:alice", 20, 99L);
        verify(identityQuery, never()).resolve(any(), any(), any());
    }

    @Test
    void deletesOnlyThroughCurrentUserApplicationContract() throws Exception {
        mockMvc.perform(delete("/api/chat/memories/7")
                        .principal(() -> "alice"))
                .andExpect(status().isOk());

        verify(operations).delete("subject:alice", 7L);
    }
}
