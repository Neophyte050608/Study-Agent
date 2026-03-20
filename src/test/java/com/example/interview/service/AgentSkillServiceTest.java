package com.example.interview.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSkillServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadSummaryAndLazyLoadDetailWithRefresh() throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        Path skillDir = skillsRoot.resolve("knowledge-retrieval");
        Files.createDirectories(skillDir);
        Path skillFile = skillDir.resolve("SKILL.md");
        Files.writeString(skillFile, skillContent("knowledge-retrieval", "检索技能", "detail-v1"), StandardCharsets.UTF_8);

        AgentSkillService service = new AgentSkillService(skillsRoot.toString());
        List<AgentSkillService.SkillSummary> summaries = service.listSkillSummaries();
        assertEquals(1, summaries.size());
        assertEquals("knowledge-retrieval", summaries.getFirst().name());
        assertEquals("检索技能", summaries.getFirst().description());

        String first = service.resolveSkillBlock("knowledge-retrieval");
        assertTrue(first.contains("detail-v1"));

        Thread.sleep(20L);
        Files.writeString(skillFile, skillContent("knowledge-retrieval", "检索技能", "detail-v2"), StandardCharsets.UTF_8);
        String second = service.resolveSkillBlock("knowledge-retrieval");
        assertTrue(second.contains("detail-v2"));
    }

    private String skillContent(String name, String description, String detail) {
        return "---\n" +
                "name: \"" + name + "\"\n" +
                "description: \"" + description + "\"\n" +
                "---\n\n" +
                "# Skill\n\n" +
                detail + "\n";
    }
}
