package com.example.interview.service;

import com.hubspot.jinjava.Jinjava;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词管理器 (PromptManager)
 *
 * 【痛点与优化思考】
 * 1. 为什么不用硬编码或 String.format？
 *    之前提示词散落在各个 Java 类里，改一个错别字或调整一个 Few-shot 样例都要重新编译发版，效率极低。
 *    String.format 无法处理复杂的条件分支（if/else）和循环（for-loop）渲染。
 * 2. 为什么选 Jinjava？
 *    - Jinjava 是 Jinja2 模板引擎的 Java 实现。Jinja2 在 Python (LangChain/LlamaIndex) 生态中是标准。
 *    - 选择它意味着未来如果将 AI 核心逻辑迁移到 Python 侧，提示词可以无缝复用，实现跨语言对齐。
 *
 * 核心职责：
 * 1. 集中管理所有 AI 提示词模板，从单个 prompts.txt 文件中解析所有提示词。
 * 2. 使用 Jinjava 模板引擎进行动态渲染，结合 Few-shot 动态注入缓解模型格式幻觉。
 * 3. 提供缓存机制，避免频繁读取文件。
 */
@Service
public class PromptManager {

    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("(?m)^---\\[(.*?)\\]---$\\r?\\n?");

    private final Jinjava jinjava;
    private final ResourceLoader resourceLoader;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();
    private final Map<String, String> systemTemplateCache = new ConcurrentHashMap<>();
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
        ensureLoaded();
        String template = templateCache.get(templateName);
        if (template == null) {
            throw new RuntimeException("找不到指定的提示词模板: " + templateName);
        }
        return jinjava.render(template, variables);
    }

    /**
     * 渲染系统模板 + 任务模板的组合。
     * 先渲染系统模板，将结果注入到任务模板的 {{ systemBlock }} 变量中，再渲染任务模板。
     */
    public String renderWithSystem(String systemTemplateName, String taskTemplateName, Map<String, Object> variables) {
        ensureLoaded();
        String systemTemplate = systemTemplateCache.get(systemTemplateName);
        String systemContent = "";
        if (systemTemplate != null) {
            systemContent = jinjava.render(systemTemplate, variables);
        }
        Map<String, Object> mergedVars = new HashMap<>(variables);
        mergedVars.put("systemBlock", systemContent);
        return render(taskTemplateName, mergedVars);
    }

    /**
     * 获取系统模板原文（用于需要直接拼接的场景）。
     */
    public String getSystemPrompt(String name) {
        ensureLoaded();
        return systemTemplateCache.getOrDefault(name, "");
    }

    private void ensureLoaded() {
        if (!isLoaded) {
            synchronized (this) {
                if (!isLoaded) {
                    loadAllTemplates();
                    isLoaded = true;
                }
            }
        }
    }

    private void loadAllTemplates() {
        templateCache.clear();
        systemTemplateCache.clear();
        loadTemplateFile("classpath:system-prompts.txt", systemTemplateCache);
        loadTemplateFile("classpath:prompts.txt", templateCache);
    }

    /**
     * 从模板文件中解析所有模板。
     * 格式约定：
     * ---[template-name]---
     * 模板内容...
     */
    private void loadTemplateFile(String path, Map<String, String> cache) {
        try {
            Resource resource = resourceLoader.getResource(path);
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);

            Matcher matcher = TEMPLATE_PATTERN.matcher(content);

            int lastEnd = 0;
            String currentName = null;

            while (matcher.find()) {
                if (currentName != null) {
                    String templateContent = content.substring(lastEnd, matcher.start()).trim();
                    cache.put(currentName, templateContent);
                }
                currentName = matcher.group(1).trim();
                lastEnd = matcher.end();
            }
            if (currentName != null && lastEnd < content.length()) {
                String templateContent = content.substring(lastEnd).trim();
                cache.put(currentName, templateContent);
            }
        } catch (IOException e) {
            throw new RuntimeException("无法加载提示词模板文件: " + path, e);
        }
    }

    /**
     * 清除模板缓存（用于开发环境下热更新提示词）
     */
    public void clearCache() {
        templateCache.clear();
        systemTemplateCache.clear();
        isLoaded = false;
    }
}
