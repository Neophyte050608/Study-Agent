package com.example.interview.service;

import com.example.interview.config.KnowledgeRetrievalProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ollama 路由服务。
 *
 * <p>职责是从候选节点中选出最相关的知识点 id，不负责读取笔记正文。</p>
 */
@Service
public class OllamaRoutingService {

    private final KnowledgeRetrievalProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;

    public OllamaRoutingService(KnowledgeRetrievalProperties properties,
                                ObjectMapper objectMapper,
                                RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClientBuilder = restClientBuilder;
    }

    public List<String> route(String question, List<KnowledgeMapService.KnowledgeNode> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.CANDIDATE_RECALL_EMPTY,
                    "No candidate nodes available for routing"
            );
        }
        if (properties.getOllamaModel().isBlank()) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.OLLAMA_UNAVAILABLE,
                    "Ollama model is not configured"
            );
        }

        RestClient restClient = buildClient();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getOllamaModel());
        body.put("stream", false);
        body.put("format", "json");
        body.put("prompt", buildPrompt(question, candidates));

        try {
            String raw = restClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseMatches(raw, candidates);
        } catch (LocalGraphRetrievalException e) {
            throw e;
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SocketTimeoutException) {
                throw new LocalGraphRetrievalException(
                        LocalGraphFailureReason.OLLAMA_TIMEOUT,
                        "Ollama routing request timed out",
                        e
                );
            }
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.OLLAMA_UNAVAILABLE,
                    "Ollama routing service is unavailable",
                    e
            );
        } catch (RestClientException e) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.OLLAMA_UNAVAILABLE,
                    "Failed to call Ollama routing service",
                    e
            );
        }
    }

    private RestClient buildClient() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(properties.getOllamaTimeoutMs()));
        return restClientBuilder
                .baseUrl(properties.getOllamaBaseUrl())
                .requestFactory(factory)
                .build();
    }

    private String buildPrompt(String question, List<KnowledgeMapService.KnowledgeNode> candidates) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                你是本地知识路由器。请只根据候选节点选择最相关的知识点 id。
                只输出 JSON，格式为 {"matches":["id1","id2"]}。
                不要输出解释，不要输出候选集外的 id，最多返回 3 个 id。

                用户问题：
                """);
        builder.append(question == null ? "" : question.trim()).append("\n\n候选节点：\n");
        for (KnowledgeMapService.KnowledgeNode node : candidates) {
            builder.append("- id=").append(node.id())
                    .append("; title=").append(node.title())
                    .append("; aliases=").append(String.join(", ", node.aliases()))
                    .append("; tags=").append(String.join(", ", node.tags()))
                    .append("; summary=").append(node.summary())
                    .append("\n");
        }
        return builder.toString();
    }

    private List<String> parseMatches(String raw, List<KnowledgeMapService.KnowledgeNode> candidates) {
        try {
            JsonNode root = objectMapper.readTree(raw == null ? "" : raw);
            String responseText = root.path("response").asText("");
            if (responseText.isBlank()) {
                throw new LocalGraphRetrievalException(
                        LocalGraphFailureReason.OLLAMA_INVALID_JSON,
                        "Ollama response is empty"
                );
            }
            JsonNode routed = objectMapper.readTree(responseText);
            JsonNode matches = routed.path("matches");
            if (!matches.isArray() || matches.isEmpty()) {
                throw new LocalGraphRetrievalException(
                        LocalGraphFailureReason.ROUTING_EMPTY,
                        "Ollama returned no matched ids"
                );
            }
            List<String> allowedIds = candidates.stream().map(KnowledgeMapService.KnowledgeNode::id).toList();
            List<String> results = new ArrayList<>();
            for (JsonNode match : matches) {
                String id = match.asText("").trim();
                if (!id.isBlank() && allowedIds.contains(id) && !results.contains(id)) {
                    results.add(id);
                }
                if (results.size() >= properties.getMaxLocalMatches()) {
                    break;
                }
            }
            if (results.isEmpty()) {
                throw new LocalGraphRetrievalException(
                        LocalGraphFailureReason.ROUTING_EMPTY,
                        "Ollama returned ids outside current candidates"
                );
            }
            return List.copyOf(results);
        } catch (LocalGraphRetrievalException e) {
            throw e;
        } catch (Exception e) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.OLLAMA_INVALID_JSON,
                    "Failed to parse Ollama routing response",
                    e
            );
        }
    }
}
