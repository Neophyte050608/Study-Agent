package io.github.imzmq.interview.mapper.ingestion;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.entity.ingestion.IngestionTaskHistoryDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IngestionTaskHistoryMapper extends BaseMapper<IngestionTaskHistoryDO> {
}


