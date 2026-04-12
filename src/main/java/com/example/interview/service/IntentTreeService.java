package com.example.interview.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.interview.entity.IntentNodeDO;
import com.example.interview.intent.IntentTreeNode;

import java.util.List;

/**
 * 意图树服务接口
 */
public interface IntentTreeService extends IService<IntentNodeDO> {

    /**
     * 加载全量叶子意图节点（带缓存）
     */
    List<IntentTreeNode> loadAllLeafIntents();

    /**
     * 加载全量域节点（level=0, enabled=1，带缓存）
     */
    List<IntentTreeNode> loadDomainNodes();

    /**
     * 加载指定域下的叶子意图节点（带缓存）
     * 通过 domain → category → leaf 的 parent_code 链路查找。
     *
     * @param domainCode 域编码（如 "INTERVIEW"）
     * @return 该域下所有启用的叶子节点 + UNKNOWN 兜底节点
     */
    List<IntentTreeNode> loadLeafIntentsByDomain(String domainCode);

    /**
     * 清除缓存
     */
    void clearCache();
}
