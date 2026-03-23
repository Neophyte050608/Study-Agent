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
     * 加载全量意图树节点（带缓存）
     *
     * @return 意图节点列表
     */
    List<IntentTreeNode> loadAllLeafIntents();

    /**
     * 清除缓存
     */
    void clearCache();
}
