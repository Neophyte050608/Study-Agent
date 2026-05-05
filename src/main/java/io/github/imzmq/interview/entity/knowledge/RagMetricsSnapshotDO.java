package io.github.imzmq.interview.entity.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_rag_metrics_snapshot")
public class RagMetricsSnapshotDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String snapshotId;
    private LocalDateTime snapshotHour;
    private Integer traceCount;
    private Long avgDurationMs;
    private Long p95DurationMs;
    private Integer successCount;
    private Integer failedCount;
    private Integer slowCount;
    private Integer fallbackCount;
    private Integer emptyRetrievalCount;
    private Double avgRetrievedDocs;
    private Integer thumbsUpCount;
    private Integer thumbsDownCount;
    private Integer copyCount;
    private Double satisfactionRate;
    private LocalDateTime createdAt;
}
