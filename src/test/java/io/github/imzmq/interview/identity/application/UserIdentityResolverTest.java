package io.github.imzmq.interview.identity.application;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class UserIdentityResolverTest {

    private final UserIdentityResolver resolver = new UserIdentityResolver();

    @Test
    void jwtModeUsesOnlyVerifiedPrincipal() {
        ReflectionTestUtils.setField(resolver, "securityMode", "jwt");

        assertThat(resolver.resolveTrusted("alice", () -> "session-1"))
                .isEqualTo("subject:alice");
        assertThat(resolver.resolveTrusted("", () -> "session-1"))
                .isEmpty();
    }

    @Test
    void jwtModeSanitizesVerifiedPrincipal() {
        ReflectionTestUtils.setField(resolver, "securityMode", "jwt");

        assertThat(resolver.resolveTrusted("  ali\nce\t ", () -> "session-1"))
                .isEqualTo("subject:alice");
    }

    @Test
    void openModeAlwaysUsesDefaultUser() {
        ReflectionTestUtils.setField(resolver, "securityMode", "open");

        assertThat(resolver.resolveTrusted("forged", () -> "session-1"))
                .isEqualTo("default-user");
    }
}
