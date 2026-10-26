package io.github.imzmq.interview.chat.application.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.imzmq.interview.chat.application.ChatSessionAccessException;
import io.github.imzmq.interview.chat.application.contract.result.ChatMessageView;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class ChatMemoryMessageTransactionService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatMemoryExtractionTaskMapper extractionTaskMapper;
    private final ChatMemoryProperties properties;

    public ChatMemoryMessageTransactionService(ChatSessionMapper sessionMapper,
                                               ChatMessageMapper messageMapper,
                                               ChatMemoryExtractionTaskMapper extractionTaskMapper,
                                               ChatMemoryProperties properties) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.extractionTaskMapper = extractionTaskMapper;
        this.properties = properties;
    }

    @Transactional
    public ChatMessageView saveUserMessage(String userId, String sessionId, String content) {
        requireOwnedSessionForUpdate(userId, sessionId);

        LocalDateTime now = LocalDateTime.now();
        ChatMessageDO message = new ChatMessageDO();
        message.setMessageId(UUID.randomUUID().toString());
        message.setSessionId(sessionId);
        message.setRole("user");
        message.setContentType("text");
        message.setContent(content);
        message.setDeleted(false);
        message.setCreatedAt(now);
        messageMapper.insert(message);

        sessionMapper.update(null,
                new LambdaUpdateWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getSessionId, sessionId)
                        .eq(ChatSessionDO::getUserId, userId)
                        .set(ChatSessionDO::getUpdatedAt, now));

        if (properties.isEnabled()) {
            ChatMemoryExtractionTaskDO task = new ChatMemoryExtractionTaskDO();
            task.setTaskId(UUID.randomUUID().toString());
            task.setUserId(userId);
            task.setSessionId(sessionId);
            task.setSourceMessageId(message.getMessageId());
            task.setSourceMessagePk(message.getId());
            task.setStatus("PENDING");
            task.setAttemptCount(0);
            task.setNextAttemptAt(now.plus(properties.getExtractionDelay()));
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            extractionTaskMapper.insert(task);
        }

        return toView(message);
    }

    private void requireOwnedSessionForUpdate(String userId, String sessionId) {
        ChatSessionDO session = sessionMapper.selectOne(
                new LambdaQueryWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getSessionId, sessionId)
                        .eq(ChatSessionDO::getUserId, userId)
                        .last("LIMIT 1 FOR UPDATE"));
        if (session == null) {
            throw new ChatSessionAccessException();
        }
    }

    private ChatMessageView toView(ChatMessageDO message) {
        return new ChatMessageView(
                message.getId(),
                message.getMessageId(),
                message.getSessionId(),
                message.getRole(),
                message.getContentType(),
                message.getContent(),
                message.getMetadata(),
                message.getDeleted(),
                message.getCreatedAt());
    }
}
