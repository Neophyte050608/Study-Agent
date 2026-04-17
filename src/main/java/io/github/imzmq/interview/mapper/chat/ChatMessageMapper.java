package io.github.imzmq.interview.mapper.chat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.entity.chat.ChatMessageDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageDO> {
}


