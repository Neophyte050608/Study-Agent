package io.github.imzmq.interview.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.knowledge.infrastructure.persistence.RagQualityEvalRunDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG 生成质量评测运行汇总 Mapper。
 */
@Mapper
public interface RagQualityEvalRunMapper extends BaseMapper<RagQualityEvalRunDO> {
}



