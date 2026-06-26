package io.github.imzmq.interview.ingestion.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 同步索引 Mapper 接口
 */
@Mapper
public interface SyncIndexMapper extends BaseMapper<SyncIndexDO> {
}


