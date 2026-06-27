package io.github.imzmq.interview.interview.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 面试会话 Mapper 接口
 */
@Mapper
public interface InterviewSessionMapper extends BaseMapper<InterviewSessionDO> {

    @Update("UPDATE t_interview_session SET context_data = JSON_SET(context_data, '$.rollingSummary', #{summary}) WHERE session_id = #{sessionId} AND deleted = 0")
    int updateRollingSummary(@Param("sessionId") String sessionId, @Param("summary") String summary);
}


