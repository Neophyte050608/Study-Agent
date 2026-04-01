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

/**
 * 提示词模板服务（Jinjava 渲染 + Few-shot 用例加载）。
 *
 * <p>该服务与 {@link PromptManager} 的分工：</p>
 * <ul>
 *     <li>{@link PromptManager}：从单一 prompts.txt 中解析并缓存“命名模板片段”</li>
 *     <li>本类：负责对任意模板字符串进行 Jinjava 渲染，并按资源路径加载/缓存 few-shot 用例 JSON</li>
 * </ul>
 *
 * <p>Few-shot 用例加载约定：</p>
 * <ul>
 *     <li>资源路径为 classpath 下的 JSON 文件，例如 {@code prompts/fewshot/xxx.json}</li>
 *     <li>解析结果为 {@code List<Map<String,Object>>}，便于直接注入模板上下文</li>
 *     <li>加载失败/文件不存在时返回空列表（降级语义），避免主链路硬失败</li>
 * </ul>
 */
@Service
public class PromptTemplateService {
    private static final Logger logger = LoggerFactory.getLogger(PromptTemplateService.class);
    private final Jinjava jinjava;
    private final ObjectMapper objectMapper;
    /**
     * few-shot 案例缓存：resourcePath -> cases。
     *
     * <p>使用 ConcurrentHashMap 保障并发下 computeIfAbsent 的线程安全。</p>
     */
    private final Map<String, List<Map<String, Object>>> casesCache = new ConcurrentHashMap<>();

    public PromptTemplateService(ObjectMapper objectMapper) {
        this.jinjava = new Jinjava();
        this.objectMapper = objectMapper;
    }

    /**
     * 渲染模板。
     *
     * <p>模板语法为 Jinjava（Jinja2 的 Java 实现），支持 if/for 等语法，用于复杂提示词拼装。</p>
     *
     * @param template 模板文本
     * @param context  渲染上下文变量
     * @return 渲染后的文本
     */
    public String render(String template, Map<String, Object> context) {
        return jinjava.render(template, context);
    }

    /**
     * 从 classpath 加载并缓存 few-shot 用例（JSON）。
     *
     * <p>降级策略：</p>
     * <ul>
     *     <li>资源不存在：记录 warn 日志并返回空列表</li>
     *     <li>解析失败：记录 error 日志并返回空列表</li>
     * </ul>
     *
     * @param resourcePath classpath 资源路径（相对路径），例如 {@code prompts/fewshot/router.json}
     * @return 用例列表；失败时返回空列表
     */
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
