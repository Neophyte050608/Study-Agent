package com.example.interview.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.interview.entity.IntentNodeDO;
import com.example.interview.intent.IntentTreeNode;
import com.example.interview.mapper.IntentNodeMapper;
import com.example.interview.service.IntentTreeService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 意图树服务实现类
 */
@Service
public class IntentTreeServiceImpl extends ServiceImpl<IntentNodeMapper, IntentNodeDO> implements IntentTreeService {

    private static final String CACHE_NAME = "intentTree";

    @Override
    @Cacheable(value = CACHE_NAME, key = "'allLeafIntents'")
    public List<IntentTreeNode> loadAllLeafIntents() {
        List<IntentNodeDO> nodes = this.list(Wrappers.<IntentNodeDO>lambdaQuery()
                .eq(IntentNodeDO::getEnabled, true));
        nodes = ensureCodingLeafIntents(nodes);

        return nodes.stream()
                .map(node -> new IntentTreeNode(
                        node.getIntentCode(),
                        node.getParentCode() != null ? node.getParentCode() + "/" + node.getIntentCode() : node.getIntentCode(),
                        node.getName(),
                        node.getDescription(),
                        node.getTaskType(),
                        node.getExamples() != null ? node.getExamples() : List.of(),
                        node.getSlotHints() != null ? node.getSlotHints() : List.of()
                ))
                .collect(Collectors.toList());
    }

    @Override
    @CacheEvict(value = CACHE_NAME, allEntries = true)
    public void clearCache() {
        // 缓存清除
    }

    // 重写增删改方法，以触发缓存清理
    @Override
    @CacheEvict(value = CACHE_NAME, allEntries = true)
    public boolean save(IntentNodeDO entity) {
        return super.save(entity);
    }

    @Override
    @CacheEvict(value = CACHE_NAME, allEntries = true)
    public boolean updateById(IntentNodeDO entity) {
        return super.updateById(entity);
    }

    @Override
    @CacheEvict(value = CACHE_NAME, allEntries = true)
    public boolean removeById(java.io.Serializable id) {
        return super.removeById(id);
    }

    private List<IntentNodeDO> ensureCodingLeafIntents(List<IntentNodeDO> currentNodes) {
        List<IntentNodeDO> source = currentNodes == null ? List.of() : currentNodes;
        Set<String> existingCodes = source.stream()
                .map(IntentNodeDO::getIntentCode)
                .filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
        List<IntentNodeDO> inserts = new ArrayList<>();
        appendCodingLeaf(inserts, existingCodes, "CODING.PRACTICE.CHOICE", "刷选择题", "刷编程选择题", List.of("选择题", "单选", "多选"), List.of("topic", "questionType=CHOICE", "difficulty", "count"));
        appendCodingLeaf(inserts, existingCodes, "CODING.PRACTICE.FILL", "刷填空题", "刷编程填空题", List.of("填空题", "补全"), List.of("topic", "questionType=FILL", "difficulty", "count"));
        appendCodingLeaf(inserts, existingCodes, "CODING.PRACTICE.ALGORITHM", "刷算法题", "刷算法实现题", List.of("算法题", "编程题"), List.of("topic", "questionType=ALGORITHM", "difficulty", "count"));
        appendCodingLeaf(inserts, existingCodes, "CODING.PRACTICE.SCENARIO", "刷场景题", "刷工程场景题", List.of("场景题", "业务场景"), List.of("topic", "difficulty", "count"));
        if (inserts.isEmpty()) {
            return source;
        }
        this.saveBatch(inserts);
        List<IntentNodeDO> merged = new ArrayList<>(source);
        merged.addAll(inserts);
        return merged;
    }

    private void appendCodingLeaf(
            List<IntentNodeDO> inserts,
            Set<String> existingCodes,
            String code,
            String name,
            String description,
            List<String> examples,
            List<String> slotHints
    ) {
        if (existingCodes.contains(code)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        IntentNodeDO node = new IntentNodeDO();
        node.setIntentCode(code);
        node.setParentCode("CODING.PRACTICE");
        node.setLevel(2);
        node.setName(name);
        node.setDescription(description);
        node.setExamples(examples);
        node.setSlotHints(slotHints);
        node.setTaskType("CODING_PRACTICE");
        node.setEnabled(true);
        node.setDeleted(false);
        node.setCreatedAt(now);
        node.setUpdatedAt(now);
        inserts.add(node);
        existingCodes.add(code);
    }
}
