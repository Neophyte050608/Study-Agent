package com.example.interview.session;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.core.InterviewSession;
import com.example.interview.entity.InterviewSessionDO;
import com.example.interview.mapper.InterviewSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 基于数据库的面试会话仓库。
 * 
 * 职责：
 * 1. 使用 MyBatis-Plus 将面试会话持久化到 MySQL 数据库中。
 */
@Component
public class DbSessionRepository implements SessionRepository {

    private static final Logger logger = LoggerFactory.getLogger(DbSessionRepository.class);
    private final InterviewSessionMapper interviewSessionMapper;

    public DbSessionRepository(InterviewSessionMapper interviewSessionMapper) {
        this.interviewSessionMapper = interviewSessionMapper;
    }

    @Override
    public InterviewSession save(InterviewSession session) {
        InterviewSessionDO existing = interviewSessionMapper.selectOne(
                Wrappers.<InterviewSessionDO>lambdaQuery().eq(InterviewSessionDO::getSessionId, session.getId())
        );
        if (existing != null) {
            existing.setCurrentStage(session.getCurrentStage() != null ? session.getCurrentStage().name() : "UNKNOWN");
            existing.setContextData(session);
            existing.setUserId(session.getUserId() != null ? session.getUserId() : "local-user");
            interviewSessionMapper.updateById(existing);
        } else {
            InterviewSessionDO newDO = new InterviewSessionDO();
            newDO.setSessionId(session.getId());
            newDO.setCurrentStage(session.getCurrentStage() != null ? session.getCurrentStage().name() : "UNKNOWN");
            newDO.setContextData(session);
            newDO.setUserId(session.getUserId() != null ? session.getUserId() : "local-user");
            interviewSessionMapper.insert(newDO);
        }
        return session;
    }

    @Override
    public Optional<InterviewSession> findById(String sessionId) {
        InterviewSessionDO existing = interviewSessionMapper.selectOne(
                Wrappers.<InterviewSessionDO>lambdaQuery().eq(InterviewSessionDO::getSessionId, sessionId)
        );
        if (existing != null && existing.getContextData() != null) {
            return Optional.of(existing.getContextData());
        }
        return Optional.empty();
    }
}
