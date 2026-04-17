package io.github.imzmq.interview.entity.ingestion;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步索引实体类
 * 映射数据库表 t_sync_index
 */
@Data
@TableName(value = "t_sync_index", autoResultMap = true)
public class SyncIndexDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String filePath;

    private String fileHash;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> docIds;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}


