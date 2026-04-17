package io.github.imzmq.interview.entity.learning;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_learning_decay_config")
public class LearningDecayConfigDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String configKey;

    private String source;

    private Integer difficultyLevel;

    private Integer halfLifeDays;

    private Double minWeight;

    private String decayCurve;

    private Boolean enabled;

    private LocalDateTime updatedAt;

    private LocalDateTime createdAt;
}


