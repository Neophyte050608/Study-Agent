package io.github.imzmq.interview.observability.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.observability.infrastructure.persistence.RagTraceDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG Trace 汇总 Mapper。
 */
@Mapper
public interface RagTraceMapper extends BaseMapper<RagTraceDO> {
}



