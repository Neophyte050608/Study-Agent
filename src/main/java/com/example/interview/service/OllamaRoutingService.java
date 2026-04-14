package com.example.interview.service;

import com.example.interview.config.KnowledgeRetrievalProperties;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.ModelRoutingException;
import com.example.interview.modelrouting.RoutingChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(OllamaRoutingService.class);
    private static final String ROUTE_TEMPLATE_NAME = "ollama-local-route";

    private final KnowledgeRetrievalProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;
    private final PromptManager promptManager;
    private final RoutingChatService routingChatService;

    public OllamaRoutingService(KnowledgeRetrievalProperties properties,
                                ObjectMapper objectMapper,
                                RestClient.Builder restClientBuilder,
                                PromptManager promptManager,
                                RoutingChatService routingChatService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClientBuilder = restClientBuilder;
        this.promptManager = promptManager;
        this.routingChatService = routingChatService;
    }

    public List<String> route(String question, List<KnowledgeMapService.KnowledgeNode> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.CANDIDATE_RECALL_EMPTY,
                    "No candidate nodes available for routing"
            );
        }
        String prompt = buildPrompt(question, candidates);
        String routedText;
        try {
            routedText = routeViaModelRouting(prompt);
        } catch (ModelRoutingException ex) {
            logger.warn("RETRIEVAL route failed, fallback to legacy ollama direct call. reason={}", ex.getMessage());
            return routeViaLegacyOllama(prompt, candidates);
        }
        return parseMatchesFromRoutedText(routedText, candidates);
    }

    private String routeViaModelRouting(String prompt) {
        String routedText = routingChatService.callWithoutFallback(
                prompt,
                ModelRouteType.RETRIEVAL,
                "local-knowledge-route"
        );
        logger.info("Retrieval routing response via model-routing: {}",
                compactForLog(routedText));
        logger.debug("Retrieval routing full response via model-routing:\n{}", routedText);
        return routedText;
    }

    private List<String> routeViaLegacyOllama(String prompt, List<KnowledgeMapService.KnowledgeNode> candidates) {
        if (properties.getOllamaModel().isBlank()) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.OLLAMA_UNAVAILABLE,
                    "No RETRIEVAL candidate available and legacy ollama model is not configured"
            );
        }

        RestClient restClient = buildClient();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getOllamaModel());
        body.put("stream", false);
        body.put("format", "json");
        body.put("prompt", prompt);

        try {
            logger.info("Ollama routing request: baseUrl={}, model={}, candidateCount={}, prompt={}",
                    properties.getOllamaBaseUrl(),
                    properties.getOllamaModel(),
                    candidates.size(),
                    compactForLog(prompt));
            logger.debug("Ollama routing full prompt:\n{}", prompt);
            String raw = restClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            logger.info("Ollama routing response: model={}, raw={}",
                    properties.getOllamaModel(),
                    compactForLog(raw));
            logger.debug("Ollama routing full response:\n{}", raw);
            return parseMatchesFromLegacyResponse(raw, candidates);
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
        StringBuilder candidateList = new StringBuilder();
        for (KnowledgeMapService.KnowledgeNode node : candidates) {
            candidateList.append("- id=").append(node.id())
                    .append("; title=").append(node.title())
                    .append("; aliases=").append(String.join(", ", node.aliases()))
                    .append("; tags=").append(String.join(", ", node.tags()))
                    .append("; summary=").append(node.summary())
                    .append("\n");
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("question", question == null ? "" : question.trim());
        variables.put("candidateList", candidateList.toString().trim());
        variables.put("maxMatches", properties.getMaxLocalMatches());
        return promptManager.render(ROUTE_TEMPLATE_NAME, variables);
    }

    private String compactForLog(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 1000) {
            return normalized;
        }
        return normalized.substring(0, 1000) + "...(truncated)";
    }

    private List<String> parseMatchesFromLegacyResponse(String raw, List<KnowledgeMapService.KnowledgeNode> candidates) {
        try {
            JsonNode root = objectMapper.readTree(raw == null ? "" : raw);
            String responseText = root.path("response").asText("");
            if (responseText.isBlank()) {
                throw new LocalGraphRetrievalException(
                        LocalGraphFailureReason.OLLAMA_INVALID_JSON,
                    "Ollama response is empty"
                );
            }
            return parseMatchesFromRoutedText(responseText, candidates);
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

    private List<String> parseMatchesFromRoutedText(String routedText, List<KnowledgeMapService.KnowledgeNode> candidates) {
        try {
            JsonNode routed = objectMapper.readTree(routedText == null ? "" : routedText);
            JsonNode matches = routed.path("matches");
            if (!matches.isArray() || matches.isEmpty()) {
                throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.ROUTING_EMPTY,
                    "Routing model returned no matched ids"
                );
            }
            List<String> allowedIds = candidates.stream().map(KnowledgeMapService.KnowledgeNode::id).toList();
            List<String> results = new ArrayList<>();
            for (JsonNode match : matches) {
                String id = match.asText("").trim();
                if (id.startsWith("id=")) {
                    id = id.substring(3).trim();
                }
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
                    "Routing model returned ids outside current candidates"
                );
            }
            return List.copyOf(results);
        } catch (LocalGraphRetrievalException e) {
            throw e;
        } catch (Exception e) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.OLLAMA_INVALID_JSON,
                    "Failed to parse routing model response",
                    e
            );
        }
    }
}
