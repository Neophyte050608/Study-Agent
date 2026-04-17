package io.github.imzmq.interview.entity.modelrouting;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("t_model_candidate")
public class ModelCandidateDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String displayName;
    private String provider;
    private String model;
    private String baseUrl;
    private String apiKeyEncrypted;
    private Integer priority;
    private Boolean isPrimary;
    private Boolean supportsThinking;
    private Boolean enabled;
    private String routeType;

    @TableLogic
    private Boolean deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}


