package io.github.imzmq.interview.entity.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_knowledge_chunk_ctrl")
public class KnowledgeChunkCtrlDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String docId;
    private String chunkId;
    private Boolean enabled;
    private LocalDateTime createdAt;
}


