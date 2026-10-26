package io.github.imzmq.interview.chat.application.contract;

import io.github.imzmq.interview.chat.application.contract.result.ChatMessageView;

import java.util.List;
import java.util.Map;

public interface ChatMessageOperations {
    List<ChatMessageView> listMessages(String sessionId, int limit, Long beforeId);

    List<ChatMessageView> listMessages(String userId, String sessionId, int limit, Long beforeId);

    ChatMessageView saveUserMessage(String sessionId, String content);

    ChatMessageView saveUserMessage(String userId, String sessionId, String content);

    ChatMessageView saveAssistantMessage(String sessionId, String content, Map<String, Object> metadata);

    ChatMessageView saveAssistantMessage(String sessionId, String content, Map<String, Object> metadata, String contentType);

    ChatMessageView createAssistantPlaceholder(String sessionId, String content, Map<String, Object> metadata,
                                               String contentType);

    void updateAssistantMessage(String messageId, String content, Map<String, Object> metadata, String contentType);

    ChatMessageView findMessageById(String messageId);
}
