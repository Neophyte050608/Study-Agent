package com.example.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 配置实体类
 * 映射数据库表 t_agent_config
 */
@Data
@TableName("t_agent_config")
public class AgentConfigDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String agentName;

    private String provider;

    private String modelName;

    private String apiKey;

    private Double temperature;

    private String systemPrompt;

    private Boolean enabled;

    @TableLogic
    private Boolean deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
