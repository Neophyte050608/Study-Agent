package io.github.imzmq.interview.interview.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.imzmq.interview.agent.runtime.TaskRouterAgent;
import io.github.imzmq.interview.agent.task.TaskResponse;
import io.github.imzmq.interview.entity.chat.ChatMessageDO;
import io.github.imzmq.interview.entity.chat.ChatSessionDO;
import io.github.imzmq.interview.interview.application.TaskResponsePresentationService;
import io.github.imzmq.interview.chat.application.ChatContextCompressor;
import io.github.imzmq.interview.mapper.chat.ChatMessageMapper;
import io.github.imzmq.interview.mapper.chat.ChatSessionMapper;
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

    // 创建新会话：生成 sessionId，绑定 userId，并入库。
    public ChatSessionDO createSession(String userId, String title) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setTitle(title != null && !title.isBlank() ? title : "新对话");
        session.setDeleted(false);
        sessionMapper.insert(session);
        return session;
    }

    // 列出用户会话，按最近更新时间倒序，满足聊天侧边栏展示。
    public List<ChatSessionDO> listSessions(String userId) {
        return sessionMapper.selectList(
                new LambdaQueryWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getUserId, userId)
                        .orderByDesc(ChatSessionDO::getUpdatedAt)
        );
    }

    // 重命名会话标题。
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

    // 删除会话（当前实现为直接删除记录）。
    public void deleteSession(String sessionId) {
        sessionMapper.delete(
                new LambdaQueryWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getSessionId, sessionId)
        );
    }

    // 清空会话上下文摘要（用于重置上下文压缩状态）。
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

    // 拉取消息列表：可按 beforeId 向前翻页，且限制最大 200 条避免一次拉取过大。
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

    // 保存用户消息（用户发言落库）。
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

    // 保存助手消息（默认 contentType=text）。
    public ChatMessageDO saveAssistantMessage(String sessionId, String content, Map<String, Object> metadata) {
        return saveAssistantMessage(sessionId, content, metadata, "text");
    }

    // 保存助手消息（可带 contentType=quiz/rich/scenario_card 等）。
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

    // 创建占位消息：流式生成开始时先落一条“正在生成中...”。
    public ChatMessageDO createAssistantPlaceholder(String sessionId,
                                                    String content,
                                                    Map<String, Object> metadata,
                                                    String contentType) {
        return saveAssistantMessage(sessionId, content, metadata, contentType);
    }

    // 更新占位消息：流式结束后把内容/状态回填。
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

    public ChatMessageDO findMessageById(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return null;
        }
        return messageMapper.selectOne(
                new LambdaQueryWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getMessageId, messageId)
                        .last("LIMIT 1")
        );
    }

    // ======== Public helpers (used by ChatStreamingService) ========

    // 构建压缩历史上下文，用于模型输入，控制 token 成本。
    public String buildHistoryContext(String sessionId) {
        return chatContextCompressor.buildCompressedContext(sessionId);
    }

    // 构建更偏向意图判定的上下文（通常比完整历史更短更聚焦）。
    public String buildIntentRoutingContext(String sessionId) {
        return chatContextCompressor.buildIntentRoutingContext(sessionId);
    }

    // 统一把任务响应转成 Web 展示文本。
    public String extractReplyText(TaskResponse response) {
        return taskResponsePresentationService.format(response, TaskResponsePresentationService.PresentationChannel.WEB);
    }

    // 若会话是首轮用户输入，则自动更新标题，提升会话列表可读性。
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

    // 当响应中要求 clearSession=true 时，表示下游明确要求重置会话上下文。
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

    // 更新会话更新时间（让会话在列表里上浮）。
    private void touchSession(String sessionId) {
        sessionMapper.update(null,
                new LambdaUpdateWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getSessionId, sessionId)
                        .set(ChatSessionDO::getUpdatedAt, LocalDateTime.now())
        );
    }
}






