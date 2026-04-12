package com.example.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.interview.agent.TaskRouterAgent;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.entity.ChatMessageDO;
import com.example.interview.entity.ChatSessionDO;
import com.example.interview.mapper.ChatMessageMapper;
import com.example.interview.mapper.ChatSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WebChatService {
    private static final Logger log = LoggerFactory.getLogger(WebChatService.class);
    private static final int TITLE_MAX_LENGTH = 30;

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final TaskRouterAgent taskRouterAgent;
    private final ChatContextCompressor chatContextCompressor;
    private final TaskResponsePresentationService taskResponsePresentationService;

    public WebChatService(ChatSessionMapper sessionMapper,
                          ChatMessageMapper messageMapper,
                          TaskRouterAgent taskRouterAgent,
                          ChatContextCompressor chatContextCompressor,
                          TaskResponsePresentationService taskResponsePresentationService) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.taskRouterAgent = taskRouterAgent;
        this.chatContextCompressor = chatContextCompressor;
        this.taskResponsePresentationService = taskResponsePresentationService;
    }

    // ======== Session CRUD ========

    public ChatSessionDO createSession(String userId, String title) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setTitle(title != null && !title.isBlank() ? title : "新对话");
        session.setDeleted(false);
        sessionMapper.insert(session);
        return session;
    }

    public List<ChatSessionDO> listSessions(String userId) {
        return sessionMapper.selectList(
                new LambdaQueryWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getUserId, userId)
                        .orderByDesc(ChatSessionDO::getUpdatedAt)
        );
    }

    public ChatSessionDO renameSession(String sessionId, String newTitle) {
        sessionMapper.update(null,
                new LambdaUpdateWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getSessionId, sessionId)
                        .set(ChatSessionDO::getTitle, newTitle)
        );
        return sessionMapper.selectOne(
                new LambdaQueryWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getSessionId, sessionId)
        );
    }

    public void deleteSession(String sessionId) {
        sessionMapper.delete(
                new LambdaQueryWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getSessionId, sessionId)
        );
    }

    public void clearSessionContext(String sessionId) {
        clearSessionContext(sessionId, null);
    }

    public void clearSessionContext(String sessionId, String boundaryMessageId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Long boundaryId = null;
        if (boundaryMessageId != null && !boundaryMessageId.isBlank()) {
            ChatMessageDO boundaryMessage = messageMapper.selectOne(
                    new LambdaQueryWrapper<ChatMessageDO>()
                            .eq(ChatMessageDO::getMessageId, boundaryMessageId)
                            .eq(ChatMessageDO::getSessionId, sessionId)
                            .last("LIMIT 1")
            );
            if (boundaryMessage != null) {
                boundaryId = boundaryMessage.getId();
            }
        }
        sessionMapper.update(null,
                new LambdaUpdateWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getSessionId, sessionId)
                        .set(ChatSessionDO::getContextSummary, null)
                        .set(ChatSessionDO::getSummaryUpToMsgId, boundaryId)
                        .set(ChatSessionDO::getUpdatedAt, LocalDateTime.now())
        );
    }

    // ======== Message Operations ========

    public List<ChatMessageDO> listMessages(String sessionId, int limit, Long beforeId) {
        LambdaQueryWrapper<ChatMessageDO> wrapper = new LambdaQueryWrapper<ChatMessageDO>()
                .eq(ChatMessageDO::getSessionId, sessionId)
                .orderByAsc(ChatMessageDO::getCreatedAt);
        if (beforeId != null) {
            wrapper.lt(ChatMessageDO::getId, beforeId);
        }
        wrapper.last("LIMIT " + Math.min(limit, 200));
        return messageMapper.selectList(wrapper);
    }

    public ChatMessageDO saveUserMessage(String sessionId, String content) {
        ChatMessageDO msg = new ChatMessageDO();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(sessionId);
        msg.setRole("user");
        msg.setContentType("text");
        msg.setContent(content);
        msg.setDeleted(false);
        messageMapper.insert(msg);
        touchSession(sessionId);
        return msg;
    }

    public ChatMessageDO saveAssistantMessage(String sessionId, String content, Map<String, Object> metadata) {
        return saveAssistantMessage(sessionId, content, metadata, "text");
    }

    public ChatMessageDO saveAssistantMessage(String sessionId, String content, Map<String, Object> metadata, String contentType) {
        ChatMessageDO msg = new ChatMessageDO();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(sessionId);
        msg.setRole("assistant");
        msg.setContentType(contentType != null ? contentType : "text");
        msg.setContent(content);
        msg.setMetadata(metadata);
        msg.setDeleted(false);
        messageMapper.insert(msg);
        touchSession(sessionId);
        return msg;
    }

    public ChatMessageDO createAssistantPlaceholder(String sessionId,
                                                    String content,
                                                    Map<String, Object> metadata,
                                                    String contentType) {
        return saveAssistantMessage(sessionId, content, metadata, contentType);
    }

    public void updateAssistantMessage(String messageId,
                                       String content,
                                       Map<String, Object> metadata,
                                       String contentType) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        ChatMessageDO existing = messageMapper.selectOne(
                new LambdaQueryWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getMessageId, messageId)
                        .last("LIMIT 1")
        );
        if (existing == null) {
            return;
        }
        existing.setContent(content);
        existing.setMetadata(metadata);
        if (contentType != null && !contentType.isBlank()) {
            existing.setContentType(contentType);
        }
        messageMapper.updateById(existing);
        touchSession(existing.getSessionId());
    }

    // ======== Public helpers (used by ChatStreamingService) ========

    public String buildHistoryContext(String sessionId) {
        return chatContextCompressor.buildCompressedContext(sessionId);
    }

    public String extractReplyText(TaskResponse response) {
        return taskResponsePresentationService.format(response, TaskResponsePresentationService.PresentationChannel.WEB);
    }

    public void autoTitleIfNeeded(String sessionId, String firstUserContent) {
        long userMsgCount = messageMapper.selectCount(
                new LambdaQueryWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getSessionId, sessionId)
                        .eq(ChatMessageDO::getRole, "user")
        );
        if (userMsgCount == 1 && firstUserContent != null && !firstUserContent.isBlank()) {
            String title = firstUserContent.length() > TITLE_MAX_LENGTH
                    ? firstUserContent.substring(0, TITLE_MAX_LENGTH) + "..."
                    : firstUserContent;
            sessionMapper.update(null,
                    new LambdaUpdateWrapper<ChatSessionDO>()
                            .eq(ChatSessionDO::getSessionId, sessionId)
                            .set(ChatSessionDO::getTitle, title)
            );
        }
    }

    public TaskRouterAgent getTaskRouterAgent() {
        return taskRouterAgent;
    }

    public boolean shouldClearSession(TaskResponse response) {
        if (!(response != null && response.data() instanceof Map<?, ?> dataMap)) {
            return false;
        }
        return Boolean.TRUE.equals(dataMap.get("clearSession"));
    }

    /**
     * 查找当前聊天会话中最近一条包含 interviewSessionId 的 assistant 消息，
     * 用于判断用户是否正在进行面试（活跃面试会话检测）。
     */
    public String findActiveInterviewSessionId(String chatSessionId) {
        List<ChatMessageDO> recentMessages = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getSessionId, chatSessionId)
                        .eq(ChatMessageDO::getRole, "assistant")
                        .orderByDesc(ChatMessageDO::getCreatedAt)
                        .last("LIMIT 5")
        );
        for (ChatMessageDO msg : recentMessages) {
            Map<String, Object> meta = msg.getMetadata();
            if (meta != null && meta.containsKey("interviewSessionId")) {
                return String.valueOf(meta.get("interviewSessionId"));
            }
        }
        return null;
    }

    /**
     * 查找当前聊天会话中最近一条包含 codingSessionId 的 assistant 消息，
     * 用于判断用户是否正在进行编码练习（活跃编码会话检测）。
     */
    public String findActiveCodingSessionId(String chatSessionId) {
        List<ChatMessageDO> recentMessages = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getSessionId, chatSessionId)
                        .eq(ChatMessageDO::getRole, "assistant")
                        .orderByDesc(ChatMessageDO::getCreatedAt)
                        .last("LIMIT 5")
        );
        for (ChatMessageDO msg : recentMessages) {
            Map<String, Object> meta = msg.getMetadata();
            if (meta != null && meta.containsKey("codingSessionId")) {
                return String.valueOf(meta.get("codingSessionId"));
            }
        }
        return null;
    }

    private void touchSession(String sessionId) {
        sessionMapper.update(null,
                new LambdaUpdateWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getSessionId, sessionId)
                        .set(ChatSessionDO::getUpdatedAt, LocalDateTime.now())
        );
    }
}
