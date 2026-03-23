package com.example.interview.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.config.IntentTreeProperties;
import com.example.interview.entity.IntentNodeDO;
import com.example.interview.service.IntentTreeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public IntentTreeController(IntentTreeProperties properties, IntentTreeService intentTreeService) {
        this.properties = properties;
        this.intentTreeService = intentTreeService;
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
            config.setName(node.getName());
            config.setDescription(node.getDescription());
            config.setTaskType(node.getTaskType());
            config.setExamples(node.getExamples() != null ? node.getExamples() : new ArrayList<>());
            config.setSlotHints(node.getSlotHints() != null ? node.getSlotHints() : new ArrayList<>());
            return config;
        }).collect(Collectors.toList());
        properties.setLeafIntents(leafIntents);
        return ResponseEntity.ok(properties);
    }

    /**
     * 更新意图树配置。
     * 支持全量更新或部分更新（取决于前端传参）。
     */
    @PostMapping("/config")
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

        // 更新叶子意图列表 (同步到数据库)
        if (newProps.getLeafIntents() != null) {
            intentTreeService.remove(Wrappers.emptyWrapper());
            List<IntentNodeDO> nodesToSave = newProps.getLeafIntents().stream().map(config -> {
                IntentNodeDO node = new IntentNodeDO();
                node.setIntentCode(config.getIntentId());
                node.setName(config.getName());
                node.setDescription(config.getDescription());
                node.setTaskType(config.getTaskType());
                node.setExamples(config.getExamples());
                node.setSlotHints(config.getSlotHints());
                node.setEnabled(true);
                node.setLevel(2); // 默认叶子节点
                return node;
            }).collect(Collectors.toList());
            intentTreeService.saveBatch(nodesToSave);
            properties.setLeafIntents(newProps.getLeafIntents());
        }

        // 更新槽位精炼示例 (如果传入了非空列表)
        if (newProps.getSlotRefineCases() != null) {
            properties.setSlotRefineCases(newProps.getSlotRefineCases());
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
        stats.put("slotRefineCaseCount", properties.getSlotRefineCases().size());
        stats.put("confidenceThreshold", properties.getConfidenceThreshold());
        stats.put("status", "healthy");
        return ResponseEntity.ok(stats);
    }
}
