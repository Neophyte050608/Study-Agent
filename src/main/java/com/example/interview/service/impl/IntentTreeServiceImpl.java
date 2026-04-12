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

import java.util.ArrayList;
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
                .eq(IntentNodeDO::getEnabled, true)
                .eq(IntentNodeDO::getLevel, 2));
        return nodes.stream().map(this::toTreeNode).collect(Collectors.toList());
    }

    @Override
    @Cacheable(value = CACHE_NAME, key = "'domainNodes'")
    public List<IntentTreeNode> loadDomainNodes() {
        List<IntentNodeDO> nodes = this.list(Wrappers.<IntentNodeDO>lambdaQuery()
                .eq(IntentNodeDO::getEnabled, true)
                .eq(IntentNodeDO::getLevel, 0));
        return nodes.stream().map(this::toTreeNode).collect(Collectors.toList());
    }

    @Override
    @Cacheable(value = CACHE_NAME, key = "'leafByDomain:' + #domainCode")
    public List<IntentTreeNode> loadLeafIntentsByDomain(String domainCode) {
        if (domainCode == null || domainCode.isBlank()) {
            return List.of();
        }
        List<IntentNodeDO> categories = this.list(Wrappers.<IntentNodeDO>lambdaQuery()
                .eq(IntentNodeDO::getParentCode, domainCode)
                .eq(IntentNodeDO::getLevel, 1));
        Set<String> categoryCodeSet = categories.stream()
                .map(IntentNodeDO::getIntentCode)
                .collect(Collectors.toSet());

        if (categoryCodeSet.isEmpty()) {
            return List.of();
        }

        List<IntentNodeDO> leaves = this.list(Wrappers.<IntentNodeDO>lambdaQuery()
                .in(IntentNodeDO::getParentCode, categoryCodeSet)
                .eq(IntentNodeDO::getEnabled, true)
                .eq(IntentNodeDO::getLevel, 2));

        IntentNodeDO unknown = this.getOne(Wrappers.<IntentNodeDO>lambdaQuery()
                .eq(IntentNodeDO::getIntentCode, "UNKNOWN")
                .eq(IntentNodeDO::getEnabled, true), false);

        List<IntentNodeDO> result = new ArrayList<>(leaves);
        if (unknown != null) {
            result.add(unknown);
        }
        return result.stream().map(this::toTreeNode).collect(Collectors.toList());
    }

    @Override
    @CacheEvict(value = CACHE_NAME, allEntries = true)
    public void clearCache() {
        // 缓存清除
    }

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

    private IntentTreeNode toTreeNode(IntentNodeDO node) {
        return new IntentTreeNode(
                node.getIntentCode(),
                node.getParentCode() != null
                        ? node.getParentCode() + "/" + node.getIntentCode()
                        : node.getIntentCode(),
                node.getName(),
                node.getDescription(),
                node.getTaskType(),
                node.getExamples() != null ? node.getExamples() : List.of(),
                node.getSlotHints() != null ? node.getSlotHints() : List.of()
        );
    }
}
