package io.github.imzmq.interview.entity.search;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "t_autocomplete_dict", autoResultMap = true)
public class AutocompleteDictDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String phrase;

    private String intentCode;

    private String category;

    private String source;

    private Integer globalHeat;

    private Boolean enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Boolean deleted;
}


