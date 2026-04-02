package com.example.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.interview.entity.PromptTemplateDO;
import com.example.interview.mapper.PromptTemplateMapper;
import com.hubspot.jinjava.Jinjava;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词管理器 (PromptManager)
 */
@Service
public class PromptManager {

    private static final Logger log = LoggerFactory.getLogger(PromptManager.class);
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("(?m)^---\\[(.*?)\\]---$\\r?\\n?");

    private final Jinjava jinjava;
    private final ResourceLoader resourceLoader;
    private final PromptTemplateMapper templateMapper;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();
    private final Map<String, String> systemTemplateCache = new ConcurrentHashMap<>();
    private boolean isLoaded = false;

    public PromptManager(ResourceLoader resourceLoader, PromptTemplateMapper templateMapper) {
        this.jinjava = new Jinjava();
        this.resourceLoader = resourceLoader;
        this.templateMapper = templateMapper;
    }

    /**
     * 加载并渲染提示词模板。
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

        List<PromptTemplateDO> templates = templateMapper.selectList(
                new LambdaQueryWrapper<PromptTemplateDO>()
        );

        if (templates == null || templates.isEmpty()) {
            seedFromFiles();
            templates = templateMapper.selectList(
                    new LambdaQueryWrapper<PromptTemplateDO>()
            );
        }

        for (PromptTemplateDO t : templates) {
            if ("SYSTEM".equals(t.getType())) {
                systemTemplateCache.put(t.getName(), t.getContent());
            } else {
                templateCache.put(t.getName(), t.getContent());
            }
        }
    }

    /**
     * 从 txt 文件解析并 seed 到 DB（仅在 DB 为空时调用）。
     */
    private void seedFromFiles() {
        log.info("提示词 DB 为空，从文件自动 seed...");

        Map<String, String[]> categoryMap = Map.of(
                "system", new String[]{"interviewer", "coding-coach", "router", "knowledge-assistant", "context-compressor"},
                "interview", new String[]{"task-router", "first-question", "evaluation", "final-report", "learning-plan"},
                "coding", new String[]{"coding-intent", "coding-question", "coding-evaluation", "coding-next-question"},
                "chat", new String[]{"knowledge-qa", "chat-context-compress", "cross-session-memorize"},
                "intent", new String[]{"intent-tree-classifier", "intent-clarification", "intent-slot-refine"}
        );
        Map<String, String> nameToCat = new HashMap<>();
        categoryMap.forEach((cat, names) -> {
            for (String n : names) {
                nameToCat.put(n, cat);
            }
        });

        Map<String, String> titleMap = Map.ofEntries(
                Map.entry("task-router", "任务路由"),
                Map.entry("intent-tree-classifier", "意图树分类"),
                Map.entry("intent-clarification", "意图澄清"),
                Map.entry("intent-slot-refine", "槽位补全"),
                Map.entry("first-question", "首题生成"),
                Map.entry("evaluation", "答案评估"),
                Map.entry("final-report", "面试总结报告"),
                Map.entry("coding-question", "编码题生成"),
                Map.entry("coding-evaluation", "编码题评估"),
                Map.entry("coding-next-question", "编码追问"),
                Map.entry("learning-plan", "学习计划"),
                Map.entry("coding-intent", "编码意图识别"),
                Map.entry("knowledge-qa", "知识问答"),
                Map.entry("chat-context-compress", "上下文压缩"),
                Map.entry("cross-session-memorize", "跨会话记忆"),
                Map.entry("interviewer", "面试官"),
                Map.entry("coding-coach", "编码教练"),
                Map.entry("router", "路由网关"),
                Map.entry("knowledge-assistant", "知识助手"),
                Map.entry("context-compressor", "上下文压缩器")
        );

        seedOneFile("classpath:system-prompts.txt", "SYSTEM", nameToCat, titleMap);
        seedOneFile("classpath:prompts.txt", "TASK", nameToCat, titleMap);
        log.info("提示词 seed 完成");
    }

    private void seedOneFile(String path, String type,
                             Map<String, String> nameToCat,
                             Map<String, String> titleMap) {
        try {
            Resource resource = resourceLoader.getResource(path);
            String fileContent = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            Matcher matcher = TEMPLATE_PATTERN.matcher(fileContent);

            int lastEnd = 0;
            String currentName = null;

            while (matcher.find()) {
                if (currentName != null) {
                    String templateContent = fileContent.substring(lastEnd, matcher.start()).trim();
                    insertTemplate(currentName, type, templateContent, nameToCat, titleMap);
                }
                currentName = matcher.group(1).trim();
                lastEnd = matcher.end();
            }
            if (currentName != null && lastEnd < fileContent.length()) {
                String templateContent = fileContent.substring(lastEnd).trim();
                insertTemplate(currentName, type, templateContent, nameToCat, titleMap);
            }
        } catch (IOException e) {
            log.warn("Seed 文件加载失败: {}", path, e);
        }
    }

    private void insertTemplate(String name, String type, String content,
                                Map<String, String> nameToCat,
                                Map<String, String> titleMap) {
        PromptTemplateDO entity = new PromptTemplateDO();
        entity.setName(name);
        entity.setType(type);
        entity.setContent(content);
        entity.setCategory(nameToCat.getOrDefault(name, "general"));
        entity.setTitle(titleMap.getOrDefault(name, name));
        entity.setIsBuiltin(true);
        templateMapper.insert(entity);
    }

    /**
     * 强制重新加载缓存（从 DB 读取最新数据）。
     */
    public void reloadCache() {
        synchronized (this) {
            loadAllTemplates();
            isLoaded = true;
        }
    }

    /**
     * 从模板文件中解析所有模板。
     * 保留该方法用于兼容/调试场景。
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
