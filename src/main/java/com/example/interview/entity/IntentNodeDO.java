package com.example.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 意图树节点实体类
 * 映射数据库表 t_intent_node
 */
@Data
@TableName(value = "t_intent_node", autoResultMap = true)
public class IntentNodeDO {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 意图唯一编码
     */
    private String intentCode;

    /**
     * 展示名称
     */
    private String name;

    /**
     * 语义描述，供 LLM 理解
     */
    private String description;

    /**
     * 父节点编码，根节点为空
     */
    private String parentCode;

    /**
     * 层级：0-DOMAIN, 1-CATEGORY, 2-TOPIC/LEAF
     */
    private Integer level;

    /**
     * Few-shot 用户查询示例 (JSON)
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> examples;

    /**
     * 槽位提取提示 (JSON)
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> slotHints;

    /**
     * 绑定的任务类型
     */
    private String taskType;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 逻辑删除
     */
    @TableLogic
    private Boolean deleted;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
