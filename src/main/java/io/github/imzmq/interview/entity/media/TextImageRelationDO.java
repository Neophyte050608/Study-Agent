package io.github.imzmq.interview.entity.media;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_text_image_relation")
public class TextImageRelationDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String textChunkId;
    private String parentDocId;
    private String imageId;
    private Integer positionInText;
    private String refSyntax;
    private LocalDateTime createdAt;
}


