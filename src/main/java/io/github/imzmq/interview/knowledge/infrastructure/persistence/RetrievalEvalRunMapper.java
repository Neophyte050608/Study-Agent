package io.github.imzmq.interview.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.knowledge.infrastructure.persistence.RetrievalEvalRunDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 检索评测运行汇总 Mapper。
 */
@Mapper
public interface RetrievalEvalRunMapper extends BaseMapper<RetrievalEvalRunDO> {
}



