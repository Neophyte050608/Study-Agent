package io.github.imzmq.interview.config.startup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PublicConfigurationSecurityTest {

    @Test
    void privateConfigurationExampleShouldReferenceEnvironmentVariables() throws IOException {
        String example = Files.readString(Path.of("application-private.example.yml"));

        assertThat(example)
                .contains("api-key: ${DEEPSEEK_API_KEY:}")
                .contains("api-key: ${ZHIPU_API_KEY:}");
    }

    @Test
    void shouldNotProvideDefaultVlmApiKeyInTrackedConfiguration() throws IOException {
        String configuration = new String(
                getClass().getResourceAsStream("/application.yml").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(configuration)
                .contains("api-key: ${APP_MULTIMODAL_VLM_API_KEY:}");
    }
}
