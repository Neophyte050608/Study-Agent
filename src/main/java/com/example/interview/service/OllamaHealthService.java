package com.example.interview.service;

import com.example.interview.config.KnowledgeRetrievalProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OllamaHealthService {

    private final KnowledgeRetrievalProperties properties;
    private final RestClient.Builder restClientBuilder;

    public OllamaHealthService(KnowledgeRetrievalProperties properties,
                               RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
    }

    public Map<String, Object> getHealthInfo() {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        String baseUrl = properties.getOllamaBaseUrl();
        String model = properties.getOllamaModel();

        result.put("baseUrl", baseUrl);
        result.put("model", model);
        result.put("serviceUp", false);
        result.put("modelReady", false);
        result.put("status", model == null || model.isBlank() ? "DEGRADED" : "DOWN");
        result.put("latencyMs", 0L);
        result.put("error", null);
        result.put("checkedAt", OffsetDateTime.now().toString());

        if (model == null || model.isBlank()) {
            result.put("error", "Ollama model is not configured");
            result.put("latencyMs", System.currentTimeMillis() - start);
            return result;
        }

        try {
            RestClient restClient = buildClient();
            Map<?, ?> response = restClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .body(Map.class);

            boolean serviceUp = response != null;
            boolean modelReady = containsModel(response, model);
            result.put("serviceUp", serviceUp);
            result.put("modelReady", modelReady);
            result.put("status", modelReady ? "UP" : "DEGRADED");
            result.put("latencyMs", System.currentTimeMillis() - start);
            if (!modelReady) {
                result.put("error", "Configured model not found in local Ollama");
            }
            return result;
        } catch (RestClientException ex) {
            result.put("latencyMs", System.currentTimeMillis() - start);
            result.put("status", "DOWN");
            result.put("error", resolveErrorMessage(ex));
            return result;
        }
    }

    private RestClient buildClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (int) Math.min(Integer.MAX_VALUE, properties.getOllamaTimeoutMs());
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return restClientBuilder
                .baseUrl(properties.getOllamaBaseUrl())
                .requestFactory(factory)
                .build();
    }

    private boolean containsModel(Map<?, ?> response, String model) {
        Object models = response == null ? null : response.get("models");
        if (!(models instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> entry) {
                Object name = entry.get("name");
                if (model.equals(String.valueOf(name))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String resolveErrorMessage(RestClientException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof SocketTimeoutException) {
            return "Ollama health check timed out";
        }
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "Ollama service is unavailable";
        }
        return message;
    }
}
