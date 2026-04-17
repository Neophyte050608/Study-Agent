package io.github.imzmq.interview.session.repository;

import io.github.imzmq.interview.interview.domain.InterviewSession;

import java.util.Optional;

/**
 * 会话仓库接口。
 */
public interface SessionRepository {
    /**
     * 保存面试会话。
     * @param session 会话实体
     * @return 已保存的会话实体
     */
    InterviewSession save(InterviewSession session);

    /**
     * 根据 ID 查找会话。
     * @param sessionId 会话 ID
     * @return 包含会话实体的 Optional
     */
    Optional<InterviewSession> findById(String sessionId);
}




