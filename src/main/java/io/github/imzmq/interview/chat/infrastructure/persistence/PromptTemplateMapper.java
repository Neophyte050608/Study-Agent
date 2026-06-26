package io.github.imzmq.interview.chat.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.PromptTemplateDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplateDO> {
}


