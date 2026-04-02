package com.example.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.interview.entity.PromptTemplateDO;
import com.example.interview.mapper.PromptTemplateMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class PromptTemplateService {
    private final PromptTemplateMapper templateMapper;
    private final PromptManager promptManager;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PromptTemplateService(PromptTemplateMapper templateMapper,
                                 PromptManager promptManager,
                                 ResourceLoader resourceLoader) {
        this.templateMapper = templateMapper;
        this.promptManager = promptManager;
        this.resourceLoader = resourceLoader;
    }

    public List<PromptTemplateDO> listAll(String category, String type) {
        LambdaQueryWrapper<PromptTemplateDO> wrapper = new LambdaQueryWrapper<>();
        if (category != null && !category.isBlank()) {
            wrapper.eq(PromptTemplateDO::getCategory, category);
        }
        if (type != null && !type.isBlank()) {
            wrapper.eq(PromptTemplateDO::getType, type);
        }
        wrapper.orderByAsc(PromptTemplateDO::getCategory, PromptTemplateDO::getName);
        return templateMapper.selectList(wrapper);
    }

    public PromptTemplateDO getByName(String name) {
        return templateMapper.selectOne(
                new LambdaQueryWrapper<PromptTemplateDO>()
                        .eq(PromptTemplateDO::getName, name)
        );
    }

    public void update(String name, String content, String title,
                       String description, String category) {
        PromptTemplateDO existing = getByName(name);
        if (existing == null) {
            throw new RuntimeException("模板不存在: " + name);
        }
        LambdaUpdateWrapper<PromptTemplateDO> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PromptTemplateDO::getName, name);
        if (content != null) updateWrapper.set(PromptTemplateDO::getContent, content);
        if (title != null) updateWrapper.set(PromptTemplateDO::getTitle, title);
        if (description != null) updateWrapper.set(PromptTemplateDO::getDescription, description);
        if (category != null) updateWrapper.set(PromptTemplateDO::getCategory, category);
        templateMapper.update(null, updateWrapper);
        promptManager.reloadCache();
    }

    public PromptTemplateDO create(String name, String category, String type,
                                   String title, String description, String content) {
        PromptTemplateDO existing = getByName(name);
        if (existing != null) {
            throw new RuntimeException("模板名称已存在: " + name);
        }
        PromptTemplateDO entity = new PromptTemplateDO();
        entity.setName(name);
        entity.setCategory(category != null ? category : "general");
        entity.setType(type != null ? type : "TASK");
        entity.setTitle(title);
        entity.setDescription(description);
        entity.setContent(content);
        entity.setIsBuiltin(false);
        templateMapper.insert(entity);
        promptManager.reloadCache();
        return entity;
    }

    public void delete(String name) {
        PromptTemplateDO existing = getByName(name);
        if (existing == null) {
            throw new RuntimeException("模板不存在: " + name);
        }
        if (Boolean.TRUE.equals(existing.getIsBuiltin())) {
            throw new RuntimeException("内置模板不可删除: " + name);
        }
        templateMapper.deleteById(existing.getId());
        promptManager.reloadCache();
    }

    public String preview(String name, Map<String, Object> variables) {
        return promptManager.render(name, variables != null ? variables : Map.of());
    }

    /**
     * 兼容旧调用方：从 classpath JSON 加载 few-shot 示例。
     */
    public List<Map<String, Object>> loadFewShotCases(String path) {
        try {
            Resource resource = resourceLoader.getResource("classpath:" + path);
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            return objectMapper.readValue(content, new TypeReference<>() {});
        } catch (IOException e) {
            return List.of();
        }
    }
}
