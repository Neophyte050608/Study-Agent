package com.example.interview.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubspot.jinjava.Jinjava;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PromptTemplateService {
    private static final Logger logger = LoggerFactory.getLogger(PromptTemplateService.class);
    private final Jinjava jinjava;
    private final ObjectMapper objectMapper;
    private final Map<String, List<Map<String, Object>>> casesCache = new ConcurrentHashMap<>();

    public PromptTemplateService(ObjectMapper objectMapper) {
        this.jinjava = new Jinjava();
        this.objectMapper = objectMapper;
    }

    public String render(String template, Map<String, Object> context) {
        return jinjava.render(template, context);
    }

    public List<Map<String, Object>> loadFewShotCases(String resourcePath) {
        return casesCache.computeIfAbsent(resourcePath, path -> {
            try {
                ClassPathResource resource = new ClassPathResource(path);
                if (!resource.exists()) {
                    logger.warn("Few-shot cases file not found: {}", path);
                    return Collections.emptyList();
                }
                try (InputStream is = resource.getInputStream()) {
                    return objectMapper.readValue(is, new TypeReference<List<Map<String, Object>>>() {});
                }
            } catch (Exception e) {
                logger.error("Failed to load few-shot cases from: {}", path, e);
                return Collections.emptyList();
            }
        });
    }
}
