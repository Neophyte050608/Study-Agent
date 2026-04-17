package io.github.imzmq.interview.mapper.knowledge;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.entity.knowledge.RagQualityEvalCaseDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG 生成质量评测单样本结果 Mapper。
 */
@Mapper
public interface RagQualityEvalCaseMapper extends BaseMapper<RagQualityEvalCaseDO> {
}



