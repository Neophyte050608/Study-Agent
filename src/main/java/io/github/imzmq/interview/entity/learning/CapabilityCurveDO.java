package io.github.imzmq.interview.entity.learning;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "t_capability_curve", autoResultMap = true)
public class CapabilityCurveDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;

    private String topic;

    private Integer eventSequence;

    @TableField(value = "scores_array", typeHandler = JacksonTypeHandler.class)
    private List<Integer> scoresArray;

    @TableField(value = "timestamps_array", typeHandler = JacksonTypeHandler.class)
    private List<String> timestampsArray;

    private String trendDirection;

    private Double trendStrength;

    private LocalDateTime updatedAt;

    private LocalDateTime createdAt;
}

