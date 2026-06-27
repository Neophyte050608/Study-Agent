package io.github.imzmq.interview.knowledge.internal.ingestion.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IngestionTaskHistoryMapper extends BaseMapper<IngestionTaskHistoryDO> {
}
