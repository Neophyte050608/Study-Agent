package io.github.imzmq.interview.entity.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "t_rag_parent", autoResultMap = true)
public class RagParentDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String parentId;
    private String filePath;
    private String sectionPath;
    private String sourceType;
    private String knowledgeTags;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> imageRefs;
    private String parentText;
    private String parentHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}


