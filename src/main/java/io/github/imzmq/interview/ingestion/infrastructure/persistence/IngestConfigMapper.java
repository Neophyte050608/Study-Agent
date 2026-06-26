package io.github.imzmq.interview.ingestion.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IngestConfigMapper extends BaseMapper<IngestConfigDO> {
}


