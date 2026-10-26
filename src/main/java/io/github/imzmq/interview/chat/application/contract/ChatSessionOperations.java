package io.github.imzmq.interview.chat.application.contract;

import io.github.imzmq.interview.chat.application.contract.result.ChatSessionView;

import java.util.List;

public interface ChatSessionOperations {
    ChatSessionView createSession(String userId, String title);

    List<ChatSessionView> listSessions(String userId);

    ChatSessionView renameSession(String sessionId, String newTitle);

    ChatSessionView renameSession(String userId, String sessionId, String newTitle);

    void deleteSession(String sessionId);

    void deleteSession(String userId, String sessionId);

    void requireOwnedSession(String userId, String sessionId);

    void clearSessionContext(String sessionId, String boundaryMessageId);

    void autoTitleIfNeeded(String sessionId, String firstUserContent);

    String findActiveInterviewSessionId(String chatSessionId);

    String findActiveCodingSessionId(String chatSessionId);
}
