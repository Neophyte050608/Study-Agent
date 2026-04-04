package com.example.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ragas Python 评测服务 HTTP 客户端。
 */
@Component
public class RagasEvalClient {

    private static final Logger log = LoggerFactory.getLogger(RagasEvalClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.eval.ragas.service-url:http://localhost:8100}")
    private String serviceUrl;

    @Value("${app.eval.ragas.timeout-seconds:300}")
    private int timeoutSeconds;

    @Value("${app.eval.ragas.llm-api-key:}")
    private String llmApiKey;

    @Value("${app.eval.ragas.llm-model:deepseek-chat}")
    private String llmModel;

    @Value("${app.eval.ragas.llm-base-url:https://api.deepseek.com/v1}")
    private String llmBaseUrl;

    @Value("${app.eval.ragas.embedding-api-key:}")
    private String embeddingApiKey;

    @Value("${app.eval.ragas.embedding-model:embedding-3}")
    private String embeddingModel;

    @Value("${app.eval.ragas.embedding-base-url:https://open.bigmodel.cn/api/paas/v4}")
    private String embeddingBaseUrl;

    public RagasEvalClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(300);
        requestFactory.setConnectTimeout((int) timeout.toMillis());
        requestFactory.setReadTimeout((int) timeout.toMillis());
        this.restTemplate = new RestTemplate(requestFactory);
    }

    public boolean isAvailable() {
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(serviceUrl + "/health", Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("Ragas eval service unavailable: {}", e.getMessage());
            return false;
        }
    }

    public Map<String, Object> getHealthInfo() {
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(serviceUrl + "/health", Map.class);
            if (response.getBody() == null) {
                return Map.of("status", "unknown");
            }
            return response.getBody();
        } catch (Exception e) {
            return Map.of("status", "unavailable", "error", e.getMessage());
        }
    }

    public List<RAGQualityEvaluationService.QualityEvalCaseResult> evaluate(
            List<RAGQualityEvaluationService.QualityEvalCase> cases
    ) {
        List<Map<String, Object>> caseMaps = new ArrayList<>();
        for (RAGQualityEvaluationService.QualityEvalCase evalCase : cases) {
            caseMaps.add(Map.of(
                    "query", evalCase.query(),
                    "answer", "",
                    "contexts", List.of(),
                    "ground_truth", evalCase.groundTruthAnswer()
            ));
        }
        List<Map<String, Object>> raw = evaluateWithPreparedData(caseMaps);
        return parseRagasResponse(Map.of("results", raw), cases);
    }

    public List<Map<String, Object>> evaluateWithPreparedData(List<Map<String, Object>> preparedCases) {
        updateTimeout();
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("cases", preparedCases == null ? List.of() : preparedCases);
        requestBody.put("metrics", List.of("faithfulness", "answer_relevancy", "answer_correctness", "context_precision", "context_recall"));

        Map<String, String> llmConfig = new LinkedHashMap<>();
        llmConfig.put("model", llmModel);
        llmConfig.put("base_url", llmBaseUrl);
        llmConfig.put("api_key", llmApiKey);
        requestBody.put("llm_config", llmConfig);

        // embedding 配置
        String effectiveEmbeddingApiKey = (embeddingApiKey != null && !embeddingApiKey.isEmpty()) ? embeddingApiKey : llmApiKey;
        if (effectiveEmbeddingApiKey != null && !effectiveEmbeddingApiKey.isEmpty()) {
            Map<String, String> embeddingConfig = new LinkedHashMap<>();
            embeddingConfig.put("model", embeddingModel);
            embeddingConfig.put("base_url", embeddingBaseUrl);
            embeddingConfig.put("api_key", effectiveEmbeddingApiKey);
            requestBody.put("embedding_config", embeddingConfig);
        }

        // 默认中文适配
        requestBody.put("language", "chinese");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    serviceUrl + "/evaluate", entity, Map.class
            );
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Ragas eval service returned error: " + response.getStatusCode());
            }
            Object rawResults = response.getBody().get("results");
            if (rawResults instanceof List<?> list) {
                List<Map<String, Object>> results = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            row.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                        results.add(row);
                    }
                }
                return results;
            }
            return List.of();
        } catch (Exception e) {
            log.error("Ragas evaluation failed: {}", e.getMessage(), e);
            throw new RuntimeException("Ragas 评测服务调用失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<RAGQualityEvaluationService.QualityEvalCaseResult> parseRagasResponse(
            Map<String, Object> responseBody,
            List<RAGQualityEvaluationService.QualityEvalCase> originalCases
    ) {
        List<Map<String, Object>> results = (List<Map<String, Object>>) responseBody.get("results");
        if (results == null) {
            return List.of();
        }

        List<RAGQualityEvaluationService.QualityEvalCaseResult> parsed = new ArrayList<>();
        for (int i = 0; i < results.size() && i < originalCases.size(); i++) {
            Map<String, Object> r = results.get(i);
            RAGQualityEvaluationService.QualityEvalCase evalCase = originalCases.get(i);
            double faith = toDouble(r.get("faithfulness"));
            double relevancy = toDouble(r.get("answer_relevancy"));
            if (relevancy == 0.0D && r.containsKey("answer_correctness")) {
                relevancy = toDouble(r.get("answer_correctness"));
            }
            double precision = toDouble(r.get("context_precision"));
            double recall = toDouble(r.get("context_recall"));
            Map<String, String> rationales;
            if (r.containsKey("rationales") && r.get("rationales") instanceof Map<?, ?> rationaleMap) {
                rationales = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : rationaleMap.entrySet()) {
                    rationales.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            } else {
                rationales = Map.of("engine", "ragas");
            }
            parsed.add(new RAGQualityEvaluationService.QualityEvalCaseResult(
                    evalCase.query(),
                    evalCase.tag(),
                    evalCase.groundTruthAnswer(),
                    "",
                    "",
                    faith,
                    relevancy,
                    precision,
                    recall,
                    rationales
            ));
        }
        return parsed;
    }

    private double toDouble(Object val) {
        if (val == null) {
            return 0.0D;
        }
        if (val instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(val.toString());
        } catch (Exception ignored) {
            return 0.0D;
        }
    }

    private void updateTimeout() {
        if (restTemplate.getRequestFactory() instanceof SimpleClientHttpRequestFactory requestFactory) {
            int timeoutMs = (int) Duration.ofSeconds(Math.max(1, timeoutSeconds)).toMillis();
            requestFactory.setConnectTimeout(timeoutMs);
            requestFactory.setReadTimeout(timeoutMs);
        }
        if (objectMapper == null) {
            log.debug("ObjectMapper not available");
        }
    }
}
