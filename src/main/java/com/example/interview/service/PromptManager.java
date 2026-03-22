package com.example.interview.service;

import com.hubspot.jinjava.Jinjava;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词管理器 (PromptManager)
 * 
 * 核心职责：
 * 1. 集中管理所有 AI 提示词模板，从单个 prompts.txt 文件中解析所有提示词。
 * 2. 使用 Jinjava 模板引擎进行动态渲染。
 * 3. 提供缓存机制，避免频繁读取文件。
 */
@Service
public class PromptManager {

    private final Jinjava jinjava;
    private final ResourceLoader resourceLoader;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();
    private boolean isLoaded = false;

    public PromptManager(ResourceLoader resourceLoader) {
        this.jinjava = new Jinjava();
        this.resourceLoader = resourceLoader;
    }

    /**
     * 加载并渲染提示词模板。
     * 
     * @param templateName 模板名称（如 "task-router"）
     * @param variables 模板变量
     * @return 渲染后的提示词文本
     */
    public String render(String templateName, Map<String, Object> variables) {
        if (!isLoaded) {
            synchronized (this) {
                if (!isLoaded) {
                    loadAllTemplates();
                    isLoaded = true;
                }
            }
        }
        String template = templateCache.get(templateName);
        if (template == null) {
            throw new RuntimeException("找不到指定的提示词模板: " + templateName);
        }
        return jinjava.render(template, variables);
    }

    /**
     * 从单一文件 prompts.txt 中解析所有模板。
     * 格式约定：
     * ---[template-name]---
     * 模板内容...
     */
    private void loadAllTemplates() {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts.txt");
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            
            Pattern pattern = Pattern.compile("(?m)^---\\[(.*?)\\]---$\\r?\\n?");
            Matcher matcher = pattern.matcher(content);
            
            int lastEnd = 0;
            String currentName = null;
            
            while (matcher.find()) {
                if (currentName != null) {
                    String templateContent = content.substring(lastEnd, matcher.start()).trim();
                    templateCache.put(currentName, templateContent);
                }
                currentName = matcher.group(1).trim();
                lastEnd = matcher.end();
            }
            if (currentName != null && lastEnd < content.length()) {
                String templateContent = content.substring(lastEnd).trim();
                templateCache.put(currentName, templateContent);
            }
        } catch (IOException e) {
            throw new RuntimeException("无法加载提示词集合文件 prompts.txt", e);
        }
    }

    /**
     * 清除模板缓存（用于开发环境下热更新提示词）
     */
    public void clearCache() {
        templateCache.clear();
        isLoaded = false;
    }
}
