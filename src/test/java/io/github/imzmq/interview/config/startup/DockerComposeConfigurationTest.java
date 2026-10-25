package io.github.imzmq.interview.config.startup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DockerComposeConfigurationTest {

    @Test
    void redisShouldStartWithoutExternalConfigFileOrBash() throws IOException {
        String compose = Files.readString(Path.of("docker-compose.yml"));

        assertThat(compose)
                .contains("image: redis:7.2-alpine")
                .contains("--requirepass")
                .doesNotContain("volumes/redis/config/redis.conf")
                .doesNotContain("/bin/bash -c \"redis-server");
    }
}
