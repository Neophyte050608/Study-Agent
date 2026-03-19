package com.example.interview.session;

import com.example.interview.core.InterviewSession;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemorySessionRepository implements SessionRepository {

    private final Map<String, InterviewSession> sessions = new ConcurrentHashMap<>();

    @Override
    public InterviewSession save(InterviewSession session) {
        sessions.put(session.getId(), session);
        return session;
    }

    @Override
    public Optional<InterviewSession> findById(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }
}
