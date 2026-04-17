package io.github.imzmq.interview.mapper.knowledge;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.entity.knowledge.RagQualityEvalRunDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG 生成质量评测运行汇总 Mapper。
 */
@Mapper
public interface RagQualityEvalRunMapper extends BaseMapper<RagQualityEvalRunDO> {
}



