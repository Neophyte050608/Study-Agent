package io.github.imzmq.interview.entity.ingestion;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "t_ingestion_task_history", autoResultMap = true)
public class IngestionTaskHistoryDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskId;
    private String pipelineName;
    private String sourceType;
    private String status;
    private Long startedAt;
    private Long endedAt;
    private Long durationMs;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> summary;

    private String errorMessage;
    private LocalDateTime createdAt;
}


