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

import java.util.List;
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
        // 查询所有启用的节点
        List<IntentNodeDO> nodes = this.list(Wrappers.<IntentNodeDO>lambdaQuery()
                .eq(IntentNodeDO::getEnabled, true));

        // 目前简单处理：将所有的叶子节点（或所有节点）映射为 IntentTreeNode
        // 实际可根据 level 进行过滤
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
}
