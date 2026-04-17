package io.github.imzmq.interview.rag;

import io.github.imzmq.interview.rag.core.ObsidianKnowledgeExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ObsidianKnowledgeExtractorTest {

    @Test
    void shouldMarkInterviewExperienceSourceType() {
        ObsidianKnowledgeExtractor extractor = new ObsidianKnowledgeExtractor();
        String markdown = """
                # 腾讯后端面经复盘
                关键词：缓存一致性, 双写
                总结：高频会问延迟双删的失败补偿策略
                - 面试官追问：如何保证最终一致性
                - 结合项目说明重试与幂等
                """;

        ObsidianKnowledgeExtractor.ExtractionResult result = extractor.extract(markdown, "notes/面经/tencent-backend.md");

        assertFalse(result.isEmpty());
        Document doc = result.documents().get(0);
        assertEquals("interview_experience", doc.getMetadata().get("source_type"));
    }
}


