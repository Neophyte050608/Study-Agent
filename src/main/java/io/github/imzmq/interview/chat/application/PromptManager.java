package io.github.imzmq.interview.chat.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.entity.chat.PromptTemplateDO;
import io.github.imzmq.interview.mapper.chat.PromptTemplateMapper;
import com.hubspot.jinjava.Jinjava;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词管理器 (PromptManager)
 */
@Service
public class PromptManager {

    private static final Logger log = LoggerFactory.getLogger(PromptManager.class);
    private static final Set<String> REQUIRED_SYSTEM_TEMPLATES = Set.of(
            "router",
            "interviewer",
            "coding-coach",
            "knowledge-assistant",
            "context-compressor",
            "turn-analyzer-system",
            "knowledge-digest-system"
    );
    private static final Set<String> REQUIRED_TASK_TEMPLATES = Set.of(
            "task-router",
            "intent-tree-classifier",
            "intent-clarification",
            "intent-slot-refine",
            "first-question",
            "evaluation",
            "final-report",
            "coding-intent",
            "coding-question",
            "coding-evaluation",
            "coding-next-question",
            "learning-plan",
            "knowledge-qa",
            "turn-analyzer-task",
            "knowledge-digest-task",
            "batch-quiz-question",
            "ollama-local-route",
            "chat-context-compress",
            "cross-session-memorize",
            "auto-dream"
    );

    private final Jinjava jinjava;
    private final PromptTemplateMapper templateMapper;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();
    private final Map<String, String> systemTemplateCache = new ConcurrentHashMap<>();
    private boolean isLoaded = false;

    /**
     * 系统提示词 + 用户提示词的结构化对。
     */
    public record PromptPair(String systemPrompt, String userPrompt) {}

    public PromptManager(PromptTemplateMapper templateMapper) {
        this.jinjava = new Jinjava();
        this.templateMapper = templateMapper;
    }

    @PostConstruct
    public void initialize() {
        ensureLoaded();
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
     * @deprecated 使用 {@link #renderSplit(String, String, Map)} 代替，配合 RoutingChatService 双消息重载实现 API 级缓存。
     */
    @Deprecated
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
     * 分别渲染系统模板和任务模板，返回结构化的 PromptPair。
     * 用于支持 API 级的 system/user 消息分离缓存。
     */
    public PromptPair renderSplit(String systemTemplateName, String taskTemplateName, Map<String, Object> variables) {
        ensureLoaded();
        String systemTemplate = systemTemplateCache.get(systemTemplateName);
        String systemContent = "";
        if (systemTemplate != null) {
            systemContent = jinjava.render(systemTemplate, variables);
        }
        String taskTemplate = templateCache.get(taskTemplateName);
        if (taskTemplate == null) {
            throw new RuntimeException("找不到指定的提示词模板: " + taskTemplateName);
        }
        String userContent = jinjava.render(taskTemplate, variables);
        return new PromptPair(systemContent, userContent);
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

        if (templates == null) {
            templates = Collections.emptyList();
        }
        for (PromptTemplateDO t : templates) {
            String normalizedContent = normalizeTemplateSyntax(t.getContent());
            if ("SYSTEM".equals(t.getType())) {
                systemTemplateCache.put(t.getName(), normalizedContent);
            } else {
                templateCache.put(t.getName(), normalizedContent);
            }
        }
        validateRequiredTemplates();
    }

    /**
     * Jinjava 2.7.x 不支持 "is empty / is not empty" 这类测试语法，
     * 这里在加载模板时做一次兼容替换，避免线上因历史模板数据报错。
     */
    private String normalizeTemplateSyntax(String template) {
        if (template == null || template.isBlank()) {
            return template;
        }
        return template
                .replaceAll("\\bis\\s+not\\s+empty\\b", "!= \"\"")
                .replaceAll("\\bis\\s+empty\\b", "== \"\"");
    }

    private void validateRequiredTemplates() {
        Set<String> missingSystems = new HashSet<>();
        for (String templateName : REQUIRED_SYSTEM_TEMPLATES) {
            if (!systemTemplateCache.containsKey(templateName)) {
                missingSystems.add(templateName);
            }
        }
        Set<String> missing = new HashSet<>();
        for (String templateName : REQUIRED_TASK_TEMPLATES) {
            if (!templateCache.containsKey(templateName)) {
                missing.add(templateName);
            }
        }
        if (!missingSystems.isEmpty() || !missing.isEmpty()) {
            String message = "提示词模板缺失，请先在 t_prompt_template 补齐后重试，缺失系统模板: "
                    + missingSystems + "，缺失任务模板: " + missing;
            log.error(message);
            throw new IllegalStateException(message);
        }
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
     * 清除模板缓存（用于开发环境下热更新提示词）
     */
    public void clearCache() {
        templateCache.clear();
        systemTemplateCache.clear();
        isLoaded = false;
    }
}





