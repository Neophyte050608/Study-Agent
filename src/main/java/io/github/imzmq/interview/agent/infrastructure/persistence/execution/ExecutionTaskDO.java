package io.github.imzmq.interview.agent.infrastructure.persistence.execution;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;
@Data
@TableName("t_agent_execution_task")
public class ExecutionTaskDO {
    private String taskId;
    private String ownerUserId;
    private String workflowType;
    private String idempotencyKey;
    private String inputHash;
    private String inputJson;
    private String definitionJson;
    private String status;
    private String leaseOwner;
    private String finalResultJson;
    private String errorCode;
    private Integer workflowVersion;
    private Integer revision;
    private Long leaseEpoch;
    private java.time.LocalDateTime leaseUntil;
    private java.time.LocalDateTime runDeadline;
    private java.time.LocalDateTime createdAt;
    private java.time.LocalDateTime updatedAt;
    private java.time.LocalDateTime finishedAt;
}
