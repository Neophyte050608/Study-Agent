package com.example.interview.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageMetadataCollectorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldUseSha256ImageIdAndPendingInitialStatus() throws Exception {
        Path image = tempDir.resolve("demo.png");
        Files.write(image, new byte[] {(byte) 0x89, 'P', 'N', 'G'});

        ImageMetadataCollector collector = new ImageMetadataCollector();
        ImageMetadataCollector.CollectedImageMetadata metadata = collector.collect(image, tempDir.toString());

        assertEquals(64, metadata.imageId().length());
        assertEquals("PENDING", metadata.summaryStatus());
        assertEquals("demo.png", metadata.imageName());
        assertTrue(metadata.relativePath().endsWith("demo.png"));
    }
}
