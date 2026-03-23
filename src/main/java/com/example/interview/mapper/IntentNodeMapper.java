package com.example.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.interview.entity.IntentNodeDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 意图树节点 Mapper 接口
 */
@Mapper
public interface IntentNodeMapper extends BaseMapper<IntentNodeDO> {
}
