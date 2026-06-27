package io.github.imzmq.interview.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.knowledge.infrastructure.persistence.RagTraceNodeDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG Trace 节点 Mapper。
 */
@Mapper
public interface RagTraceNodeMapper extends BaseMapper<RagTraceNodeDO> {
}



