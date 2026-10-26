package io.github.imzmq.interview.chat.domain.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMemoryNormalizerTest {

    @Test
    void normalizesNfkcAndUnicodeWhitespaceWithoutChangingCase() {
        assertThat(ChatMemoryNormalizer.normalize("  Ｊava\u00A0\tLOVER  "))
                .isEqualTo("Java LOVER");
    }

    @Test
    void contentHashIsStableAndCategorySensitive() {
        String first = ChatMemoryNormalizer.contentHash(
                ChatMemoryCategory.PREFERENCE, "  I prefer Java  ");
        String equivalent = ChatMemoryNormalizer.contentHash(
                ChatMemoryCategory.PREFERENCE, "I\u00A0prefer Java");
        String anotherCategory = ChatMemoryNormalizer.contentHash(
                ChatMemoryCategory.FACT, "I prefer Java");

        assertThat(first)
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isEqualTo(equivalent)
                .isNotEqualTo(anotherCategory);
    }

    @Test
    void slicesByUnicodeCodePointOffsets() {
        assertThat(ChatMemoryNormalizer.codePointSlice("A😀偏好Java", 1, 4))
                .isEqualTo("😀偏好");
    }
}
