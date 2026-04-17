package io.github.imzmq.interview.mapper.agent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.entity.agent.AgentConfigDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 配置 Mapper 接口
 */
@Mapper
public interface AgentConfigMapper extends BaseMapper<AgentConfigDO> {
}


