package io.github.imzmq.interview.entity.learning;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 学习画像实体类
 * 映射数据库表 t_learning_profile
 */
@Data
@TableName(value = "t_learning_profile", autoResultMap = true)
public class LearningProfileDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;

    private Integer totalEvents;

    /**
     * 存储各知识点掌握度统计
     * 对应 TopicMetricState
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> topicMetrics;

    private Double reliabilityScore;

    private LocalDateTime lastUpdatedAt;

    private LocalDateTime createdAt;
}


