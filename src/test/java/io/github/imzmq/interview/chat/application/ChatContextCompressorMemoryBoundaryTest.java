package io.github.imzmq.interview.chat.application;

import io.github.imzmq.interview.chat.infrastructure.persistence.UserChatMemoryMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ChatContextCompressorMemoryBoundaryTest {

    @Test
    void compressorNoLongerDependsOnTheLegacyMemoryTextMapper() {
        assertThat(Arrays.stream(ChatContextCompressor.class.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())))
                .doesNotContain(UserChatMemoryMapper.class);
    }
}
