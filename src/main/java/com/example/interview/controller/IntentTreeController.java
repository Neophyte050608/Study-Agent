package com.example.interview.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.config.IntentTreeProperties;
import com.example.interview.entity.IntentNodeDO;
import com.example.interview.service.IntentSlotRefineCaseService;
import com.example.interview.service.IntentTreeService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 意图树配置管理控制器。
 * 
 * 职责：
 * 1. 提供意图树核心参数（阈值、开关等）的查询与修改。
 * 2. 提供叶子意图节点（LeafIntents）的管理。
 * 3. 提供槽位精炼示例（SlotRefineCases）的管理。
 */
@RestController
@RequestMapping("/api/intent-tree")
public class IntentTreeController {

    private final IntentTreeProperties properties;
    private final IntentTreeService intentTreeService;
    private final IntentSlotRefineCaseService slotRefineCaseService;

    public IntentTreeController(
            IntentTreeProperties properties,
            IntentTreeService intentTreeService,
            IntentSlotRefineCaseService slotRefineCaseService
    ) {
        this.properties = properties;
        this.intentTreeService = intentTreeService;
        this.slotRefineCaseService = slotRefineCaseService;
    }

    /**
     * 获取当前完整的意图树配置。
     */
    @GetMapping("/config")
    public ResponseEntity<IntentTreeProperties> getConfig() {
        // 从数据库加载叶子意图，并覆盖 properties 中的配置（仅供前端展示）
        List<IntentNodeDO> nodes = intentTreeService.list(Wrappers.<IntentNodeDO>lambdaQuery().eq(IntentNodeDO::getEnabled, true));
        List<IntentTreeProperties.LeafIntentConfig> leafIntents = nodes.stream().map(node -> {
            IntentTreeProperties.LeafIntentConfig config = new IntentTreeProperties.LeafIntentConfig();
            config.setIntentId(node.getIntentCode());
            config.setPath(buildPath(node.getParentCode(), node.getIntentCode()));
            config.setName(node.getName());
            config.setDescription(node.getDescription());
            config.setTaskType(node.getTaskType());
            config.setExamples(node.getExamples() != null ? node.getExamples() : new ArrayList<>());
            config.setSlotHints(node.getSlotHints() != null ? node.getSlotHints() : new ArrayList<>());
            return config;
        }).collect(Collectors.toList());
        properties.setLeafIntents(leafIntents);
        properties.setSlotRefineCases(slotRefineCaseService.listEnabled());
        return ResponseEntity.ok(properties);
    }

    /**
     * 更新意图树配置。
     * 支持全量更新或部分更新（取决于前端传参）。
     */
    @PostMapping("/config")
    @Transactional
    public ResponseEntity<IntentTreeProperties> updateConfig(@RequestBody IntentTreeProperties newProps) {
        if (newProps == null) {
            return ResponseEntity.badRequest().build();
        }

        // 更新基础参数
        properties.setEnabled(newProps.isEnabled());
        properties.setConfidenceThreshold(newProps.getConfidenceThreshold());
        properties.setMinGap(newProps.getMinGap());
        properties.setAmbiguityRatio(newProps.getAmbiguityRatio());
        properties.setClarificationTtlMinutes(newProps.getClarificationTtlMinutes());
        properties.setMaxCandidates(newProps.getMaxCandidates());
        properties.setFallbackToLegacyTaskRouter(newProps.isFallbackToLegacyTaskRouter());

        List<IntentNodeDO> existingNodes = intentTreeService.list(
                Wrappers.<IntentNodeDO>lambdaQuery()
        );
        Map<String, IntentNodeDO> existingByCode = new HashMap<>();
        for (IntentNodeDO existingNode : existingNodes) {
            String code = normalize(existingNode.getIntentCode());
            if (!code.isBlank()) {
                existingByCode.put(code, existingNode);
            }
        }

        if (newProps.getLeafIntents() != null) {
            List<IntentTreeProperties.LeafIntentConfig> normalizedLeafIntents = normalizeLeafIntents(newProps.getLeafIntents());
            Set<String> incomingCodes = new LinkedHashSet<>();
            for (IntentTreeProperties.LeafIntentConfig config : normalizedLeafIntents) {
                String code = normalize(config.getIntentId());
                if (!incomingCodes.add(code)) {
                    throw new IllegalArgumentException("叶子意图编码重复: " + code);
                }
                IntentNodeDO existing = existingByCode.get(code);
                if (existing == null) {
                    IntentNodeDO node = new IntentNodeDO();
                    node.setIntentCode(code);
                    node.setName(config.getName());
                    node.setDescription(config.getDescription());
                    node.setTaskType(config.getTaskType());
                    node.setExamples(config.getExamples());
                    node.setSlotHints(config.getSlotHints());
                    node.setParentCode(resolveParentCode(config));
                    node.setEnabled(true);
                    node.setLevel(2);
                    intentTreeService.save(node);
                    continue;
                }
                existing.setName(config.getName());
                existing.setDescription(config.getDescription());
                existing.setTaskType(config.getTaskType());
                existing.setExamples(config.getExamples());
                existing.setSlotHints(config.getSlotHints());
                existing.setParentCode(resolveParentCode(config));
                existing.setEnabled(true);
                existing.setLevel(2);
                intentTreeService.updateById(existing);
            }
            for (IntentNodeDO existing : existingNodes) {
                String code = normalize(existing.getIntentCode());
                if (code.isBlank() || incomingCodes.contains(code) || !Boolean.TRUE.equals(existing.getEnabled())) {
                    continue;
                }
                existing.setEnabled(false);
                intentTreeService.updateById(existing);
            }
            intentTreeService.clearCache();
            properties.setLeafIntents(normalizedLeafIntents);
        }

        if (newProps.getSlotRefineCases() != null) {
            slotRefineCaseService.replaceAll(newProps.getSlotRefineCases());
            properties.setSlotRefineCases(slotRefineCaseService.listEnabled());
        }

        return ResponseEntity.ok(properties);
    }

    /**
     * 获取意图引擎的运行快照统计。
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", properties.isEnabled());
        long count = intentTreeService.count(Wrappers.<IntentNodeDO>lambdaQuery().eq(IntentNodeDO::getEnabled, true));
        stats.put("leafIntentCount", count);
        stats.put("slotRefineCaseCount", slotRefineCaseService.countEnabled());
        stats.put("confidenceThreshold", properties.getConfidenceThreshold());
        stats.put("status", "healthy");
        return ResponseEntity.ok(stats);
    }

    private List<IntentTreeProperties.LeafIntentConfig> normalizeLeafIntents(List<IntentTreeProperties.LeafIntentConfig> leafIntents) {
        List<IntentTreeProperties.LeafIntentConfig> normalized = new ArrayList<>();
        for (IntentTreeProperties.LeafIntentConfig leafIntent : leafIntents) {
            if (leafIntent == null) {
                continue;
            }
            String intentId = normalize(leafIntent.getIntentId());
            if (intentId.isBlank()) {
                continue;
            }
            IntentTreeProperties.LeafIntentConfig item = new IntentTreeProperties.LeafIntentConfig();
            item.setIntentId(intentId);
            item.setPath(normalize(leafIntent.getPath()));
            item.setName(normalize(leafIntent.getName()));
            item.setDescription(normalize(leafIntent.getDescription()));
            item.setTaskType(normalize(leafIntent.getTaskType()));
            item.setExamples(normalizeArray(leafIntent.getExamples()));
            item.setSlotHints(normalizeArray(leafIntent.getSlotHints()));
            normalized.add(item);
        }
        return normalized;
    }

    private List<String> normalizeArray(List<String> values) {
        List<String> normalized = new ArrayList<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            String text = normalize(value);
            if (!text.isBlank()) {
                normalized.add(text);
            }
        }
        return normalized;
    }

    private String resolveParentCode(IntentTreeProperties.LeafIntentConfig config) {
        String path = normalize(config.getPath());
        String intentId = normalize(config.getIntentId());
        if (!path.isBlank()) {
            int slashIndex = path.lastIndexOf("/");
            if (slashIndex > 0) {
                String parentFromPath = normalize(path.substring(0, slashIndex));
                if (!parentFromPath.isBlank()) {
                    return parentFromPath;
                }
            }
        }
        int dotIndex = intentId.lastIndexOf(".");
        if (dotIndex > 0) {
            return normalize(intentId.substring(0, dotIndex));
        }
        return "";
    }

    private String buildPath(String parentCode, String intentId) {
        String normalizedParent = normalize(parentCode);
        String normalizedIntentId = normalize(intentId);
        if (normalizedIntentId.isBlank()) {
            return "";
        }
        if (normalizedParent.isBlank()) {
            return normalizedIntentId;
        }
        return normalizedParent + "/" + normalizedIntentId;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
