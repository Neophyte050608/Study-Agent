package com.example.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.interview.entity.AgentConfigDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 配置 Mapper 接口
 */
@Mapper
public interface AgentConfigMapper extends BaseMapper<AgentConfigDO> {
}
