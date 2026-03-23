package com.example.interview.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.config.AgentConfig;
import com.example.interview.entity.AgentConfigDO;
import com.example.interview.mapper.AgentConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 动态配置服务。
 * 
 * 职责：
 * 1. 管理 Agent 数据库配置的持久化。
 * 2. 提供基于 Redis 缓存的运行时配置查询。
 */
@Service
public class AgentConfigService {

    private static final Logger logger = LoggerFactory.getLogger(AgentConfigService.class);
    private final AgentConfigMapper agentConfigMapper;

    public AgentConfigService(AgentConfigMapper agentConfigMapper) {
        this.agentConfigMapper = agentConfigMapper;
    }

    /**
     * 获取指定 Agent 的配置，如果不存在则返回默认开启的配置。
     */
    @Cacheable(value = "agentConfig", key = "#agentName")
    public AgentConfig getConfig(String agentName) {
        AgentConfigDO agentConfigDO = agentConfigMapper.selectOne(
                Wrappers.<AgentConfigDO>lambdaQuery().eq(AgentConfigDO::getAgentName, agentName)
        );
        if (agentConfigDO != null) {
            return new AgentConfig(
                    agentConfigDO.getEnabled(),
                    agentConfigDO.getProvider(),
                    agentConfigDO.getModelName(),
                    agentConfigDO.getApiKey(),
                    agentConfigDO.getTemperature()
            );
        }
        return new AgentConfig(true, "OPENAI", "gpt-4o", "", 0.7);
    }

    /**
     * 更新单个 Agent 的配置并持久化。
     */
    @CacheEvict(value = "agentConfig", key = "#agentName")
    public void updateConfig(String agentName, AgentConfig newConfig) {
        AgentConfigDO existing = agentConfigMapper.selectOne(
                Wrappers.<AgentConfigDO>lambdaQuery().eq(AgentConfigDO::getAgentName, agentName)
        );
        if (existing != null) {
            existing.setEnabled(newConfig.isEnabled());
            existing.setProvider(newConfig.getProvider());
            existing.setModelName(newConfig.getModelName());
            existing.setApiKey(newConfig.getApiKey());
            existing.setTemperature(newConfig.getTemperature());
            agentConfigMapper.updateById(existing);
        } else {
            AgentConfigDO newDO = new AgentConfigDO();
            newDO.setAgentName(agentName);
            newDO.setEnabled(newConfig.isEnabled());
            newDO.setProvider(newConfig.getProvider());
            newDO.setModelName(newConfig.getModelName());
            newDO.setApiKey(newConfig.getApiKey());
            newDO.setTemperature(newConfig.getTemperature());
            agentConfigMapper.insert(newDO);
        }
    }

    /**
     * 批量更新配置
     */
    @CacheEvict(value = "agentConfig", allEntries = true)
    public void updateAllConfigs(Map<String, AgentConfig> newConfigs) {
        newConfigs.forEach(this::updateConfig);
    }

    /**
     * 获取所有配置
     */
    public Map<String, AgentConfig> getAllConfigs() {
        List<AgentConfigDO> all = agentConfigMapper.selectList(Wrappers.emptyWrapper());
        Map<String, AgentConfig> map = new HashMap<>();
        for (AgentConfigDO agentConfigDO : all) {
            map.put(agentConfigDO.getAgentName(), new AgentConfig(
                    agentConfigDO.getEnabled(),
                    agentConfigDO.getProvider(),
                    agentConfigDO.getModelName(),
                    agentConfigDO.getApiKey(),
                    agentConfigDO.getTemperature()
            ));
        }
        return map;
    }
}
