package io.github.imzmq.interview.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.knowledge.infrastructure.persistence.RagFeedbackDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RagFeedbackMapper extends BaseMapper<RagFeedbackDO> {
}
