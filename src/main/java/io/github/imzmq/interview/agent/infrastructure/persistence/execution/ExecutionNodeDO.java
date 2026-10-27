package io.github.imzmq.interview.agent.infrastructure.persistence.execution;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;
@Data
@TableName("t_agent_execution_node")
public class ExecutionNodeDO {
    private String taskId;
    private String nodeId;
    private String status;
    private String currentAttemptId;
    private String outputJson;
    private String errorCode;
    private Integer attemptCount;
}
