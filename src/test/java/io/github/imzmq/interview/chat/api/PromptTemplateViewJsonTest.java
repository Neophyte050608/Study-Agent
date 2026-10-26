package io.github.imzmq.interview.chat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.chat.application.dto.PromptTemplateView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTemplateViewJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void preservesIsBuiltinResponseFieldAfterPersistenceDtoIsHidden() throws Exception {
        PromptTemplateView view = new PromptTemplateView(
                1L,
                "memory-extraction",
                "chat-memory",
                "TASK",
                "Memory extraction",
                "Extract structured memory",
                "prompt content",
                true,
                null,
                null
        );

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(view));

        assertThat(json.path("isBuiltin").asBoolean()).isTrue();
        assertThat(json.has("builtin")).isFalse();
    }
}
