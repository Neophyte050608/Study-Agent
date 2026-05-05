package io.github.imzmq.interview.mapper.knowledge;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.entity.knowledge.RagFeedbackDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RagFeedbackMapper extends BaseMapper<RagFeedbackDO> {
}
