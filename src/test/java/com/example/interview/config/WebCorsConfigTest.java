package com.example.interview.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        value = WebCorsConfigTest.TestApiController.class,
        properties = {"app.security.allowed-origins=http://localhost:5173,http://127.0.0.1:5173"}
)
@Import({WebCorsConfig.class, SecurityConfig.class})
class WebCorsConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldAllowConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/ping")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void shouldRejectUnknownOrigin() throws Exception {
        mockMvc.perform(options("/api/ping")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @RestController
    static class TestApiController {
        @GetMapping("/api/ping")
        ResponseEntity<Map<String, Boolean>> ping() {
            return ResponseEntity.ok(Map.of("ok", true));
        }
    }
}
