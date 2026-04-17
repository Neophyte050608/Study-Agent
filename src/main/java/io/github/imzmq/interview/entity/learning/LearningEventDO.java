package io.github.imzmq.interview.entity.learning;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 学习事件实体类
 * 映射数据库表 t_learning_event
 */
@Data
@TableName(value = "t_learning_event", autoResultMap = true)
public class LearningEventDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;

    private String userId;

    private String source;

    private String topic;

    private Integer score;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> weakPoints;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> familiarPoints;

    private String evidence;

    private LocalDateTime timestamp;

    private LocalDateTime createdAt;
}


