package com.example.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.interview.entity.SyncIndexDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 同步索引 Mapper 接口
 */
@Mapper
public interface SyncIndexMapper extends BaseMapper<SyncIndexDO> {
}
