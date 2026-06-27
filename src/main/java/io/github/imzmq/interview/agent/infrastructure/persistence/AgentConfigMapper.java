package io.github.imzmq.interview.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 配置 Mapper 接口
 */
@Mapper
public interface AgentConfigMapper extends BaseMapper<AgentConfigDO> {
}


