package io.github.imzmq.interview.interview.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendRouteControllerTest {

    @Test
    void forwardsOnlyCurrentSpaEntryPaths() throws NoSuchMethodException {
        Method method = FrontendRouteController.class.getDeclaredMethod("forwardToSpa");
        GetMapping mapping = method.getAnnotation(GetMapping.class);

        assertThat(mapping.value()).containsExactlyInAnyOrder(
                "/",
                "/chat", "/chat/**",
                "/notes", "/notes/**",
                "/profile", "/profile/**",
                "/learning", "/learning/**",
                "/admin", "/admin/**"
        );
        assertThat(Set.of(mapping.value())).doesNotContain(
                "/interview", "/coding", "/practice", "/monitoring", "/ops",
                "/settings", "/workspace", "/mcp", "/intent-tree", "/app"
        );
    }
}
