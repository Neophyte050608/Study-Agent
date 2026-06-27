package io.github.imzmq.interview.knowledge.application.retrieval;

import io.github.imzmq.interview.search.application.WebSearchTool;
import io.github.imzmq.interview.skill.client.SkillMcpClient;
import io.github.imzmq.interview.skill.core.SkillDefinition;
import io.github.imzmq.interview.skill.core.SkillExecutionBudget;
import io.github.imzmq.interview.skill.core.SkillExecutionContext;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class WebFallbackService {

    private final SkillOrchestrator skillOrchestrator;
    private final SkillMcpClient skillMcpClient;
    private final WebSearchTool webSearchTool;

    public WebFallbackService(SkillOrchestrator skillOrchestrator,
                              SkillMcpClient skillMcpClient,
                              WebSearchTool webSearchTool) {
        this.skillOrchestrator = skillOrchestrator;
        this.skillMcpClient = skillMcpClient;
        this.webSearchTool = webSearchTool;
    }

    public List<String> search(String query,
                               String traceId,
                               String reason,
                               SkillExecutionBudget skillBudget) {
        SkillDefinition definition = skillOrchestrator.definition("evidence-evaluator");
        SkillExecutionContext skillContext = new SkillExecutionContext(
                traceId,
                "rag-service",
                Map.of(
                        "query", query == null ? "" : query,
                        "reason", reason == null ? "" : reason
                ),
                skillBudget
        );
        Map<String, Object> mcpResult = skillMcpClient.invokeForSkill(
                "rag-service",
                definition,
                skillContext,
                "web.search",
                Map.of(
                        "query", query == null ? "" : query,
                        "limit", 3,
                        "reason", reason == null ? "" : reason
                )
        );
        List<String> snippets = extractSearchSnippets(mcpResult);
        if (!snippets.isEmpty()) {
            return snippets;
        }
        return webSearchTool.run(new WebSearchTool.Query(query, 3));
    }

    private List<String> extractSearchSnippets(Map<String, Object> mcpResult) {
        if (mcpResult == null || mcpResult.isEmpty()) {
            return List.of();
        }
        Object result = mcpResult.get("result");
        if (result instanceof List<?> list) {
            return stringifySearchList(list);
        }
        if (result instanceof Map<?, ?> map) {
            Object items = map.get("results");
            if (!(items instanceof List<?>)) {
                items = map.get("items");
            }
            if (!(items instanceof List<?>)) {
                items = map.get("snippets");
            }
            if (items instanceof List<?> list) {
                return stringifySearchList(list);
            }
            Object content = map.get("content");
            if (content instanceof String text && !text.isBlank()) {
                return List.of(text.trim());
            }
        }
        return List.of();
    }

    private List<String> stringifySearchList(List<?> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
                .map(this::searchSnippetOf)
                .filter(item -> item != null && !item.isBlank())
                .limit(3)
                .toList();
    }

    private String searchSnippetOf(Object item) {
        if (item instanceof String text) {
            return text.trim();
        }
        if (item instanceof Map<?, ?> map) {
            Object title = map.get("title");
            Object snippet = map.get("snippet");
            if (snippet == null) {
                snippet = map.get("content");
            }
            if (snippet == null) {
                snippet = map.get("summary");
            }
            String titleText = title == null ? "" : String.valueOf(title).trim();
            String snippetText = snippet == null ? "" : String.valueOf(snippet).trim();
            if (!titleText.isBlank() && !snippetText.isBlank()) {
                return titleText + " - " + snippetText;
            }
            return snippetText;
        }
        return item == null ? "" : String.valueOf(item).trim();
    }
}
