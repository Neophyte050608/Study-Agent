package com.example.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.interview.entity.LexicalIndexDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 词法索引 Mapper 接口
 */
@Mapper
public interface LexicalIndexMapper extends BaseMapper<LexicalIndexDO> {
}
