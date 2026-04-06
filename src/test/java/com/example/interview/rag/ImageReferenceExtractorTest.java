package com.example.interview.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageReferenceExtractorTest {

    @Test
    void shouldExtractObsidianAndMarkdownImageRefs() {
        ImageReferenceExtractor extractor = new ImageReferenceExtractor();
        String markdown = """
                # 标题
                这里有一个图片 ![[arch.png]]
                还有一个 Markdown 图片 ![](attachments/code.jpg)
                """;

        List<ImageReferenceExtractor.ImageReference> refs =
                extractor.extract(markdown, "D:/vault/notes/demo.md", "D:/vault/attachments");

        assertEquals(2, refs.size());
        assertEquals("arch.png", refs.get(0).imageName());
        assertEquals("code.jpg", refs.get(1).imageName());
    }

    @Test
    void shouldSupportObsidianAliasAndInjectSummaryOnceByOffset() {
        ImageReferenceExtractor extractor = new ImageReferenceExtractor();
        String markdown = """
                A ![[image.png|300]]
                B ![[image.png|300]]
                """;

        List<ImageReferenceExtractor.ImageReference> refs =
                extractor.extract(markdown, "D:/vault/notes/demo.md", "D:/vault/attachments");
        String enriched = extractor.embedSummaries(markdown, refs);

        assertEquals(2, refs.size());
        assertEquals("image.png", refs.get(0).imageName());
        assertTrue(enriched.contains("[图片摘要] 图片：image.png"));
        assertEquals(2, enriched.split("\\[图片摘要\\]").length - 1);
    }
}
