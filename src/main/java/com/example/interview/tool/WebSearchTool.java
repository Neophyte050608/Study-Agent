package com.example.interview.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class WebSearchTool implements ToolGateway<WebSearchTool.Query, List<String>> {

    private static final Logger logger = LoggerFactory.getLogger(WebSearchTool.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<String> run(Query input) {
        if (input == null || input.query() == null || input.query().isBlank()) {
            return List.of();
        }
        try {
            int topK = input.topK() == null || input.topK() < 1 ? 3 : Math.min(input.topK(), 5);
            String url = UriComponentsBuilder.fromHttpUrl("https://api.duckduckgo.com/")
                    .queryParam("q", input.query())
                    .queryParam("format", "json")
                    .queryParam("no_html", "1")
                    .queryParam("no_redirect", "1")
                    .encode(StandardCharsets.UTF_8)
                    .toUriString();
            String response = restTemplate.getForObject(url, String.class);
            if (response == null || response.isBlank()) {
                return List.of();
            }
            JsonNode root = objectMapper.readTree(response);
            List<String> snippets = new ArrayList<>();
            String abstractText = root.path("AbstractText").asText("");
            if (!abstractText.isBlank()) {
                snippets.add(abstractText.trim());
            }
            JsonNode related = root.path("RelatedTopics");
            if (related.isArray()) {
                for (JsonNode topic : related) {
                    if (snippets.size() >= topK) {
                        break;
                    }
                    String text = topic.path("Text").asText("");
                    if (!text.isBlank()) {
                        snippets.add(text.trim());
                    }
                    JsonNode children = topic.path("Topics");
                    if (children.isArray()) {
                        for (JsonNode child : children) {
                            if (snippets.size() >= topK) {
                                break;
                            }
                            String childText = child.path("Text").asText("");
                            if (!childText.isBlank()) {
                                snippets.add(childText.trim());
                            }
                        }
                    }
                }
            }
            return snippets.stream().limit(topK).toList();
        } catch (Exception e) {
            logger.warn("Web search fallback failed: {}", e.getMessage());
            return List.of();
        }
    }

    public record Query(String query, Integer topK) {
    }
}
