package com.example.interview.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserIdentityResolverTest {

    private final UserIdentityResolver resolver = new UserIdentityResolver();

    @Test
    void shouldResolveJwtSubjectFirst() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + buildJwt("{\"sub\":\"user-123\"}"));

        String userId = resolver.resolve(request);

        assertEquals("subject:user-123", userId);
    }

    @Test
    void shouldFallbackToHeaderWhenNoBearer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "abc");

        String userId = resolver.resolve(request);

        assertEquals("header:abc", userId);
    }

    @Test
    void shouldIgnoreExpiredJwtAndFallbackToHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + buildJwt("{\"sub\":\"user-123\",\"exp\":1}"));
        request.addHeader("X-User-Id", "backup-user");

        String userId = resolver.resolve(request);

        assertEquals("header:backup-user", userId);
    }

    @Test
    void shouldFallbackToSessionWhenNoIdentityHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        String userId = resolver.resolve(request);

        assertTrue(userId.startsWith("session:"));
    }

    private String buildJwt(String payload) {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String body = base64Url(payload);
        return header + "." + body + ".";
    }

    private String base64Url(String json) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
