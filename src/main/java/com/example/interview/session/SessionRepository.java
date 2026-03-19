package com.example.interview.session;

import com.example.interview.core.InterviewSession;

import java.util.Optional;

public interface SessionRepository {
    InterviewSession save(InterviewSession session);

    Optional<InterviewSession> findById(String sessionId);
}
