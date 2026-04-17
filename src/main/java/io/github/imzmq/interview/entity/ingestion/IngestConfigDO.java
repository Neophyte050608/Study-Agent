package io.github.imzmq.interview.entity.ingestion;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 入库配置实体类。
 * 映射数据库表 t_ingest_config。
 */
@Data
@TableName("t_ingest_config")
public class IngestConfigDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String configKey;

    private String paths;

    private String imagePath;

    private String ignoreDirs;

    @TableLogic
    private Boolean deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}


