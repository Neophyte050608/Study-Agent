package com.example.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.interview.entity.ChatMessageDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageDO> {
}
