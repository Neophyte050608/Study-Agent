package io.github.imzmq.interview.chat.application;

import io.github.imzmq.interview.chat.application.contract.result.RenderedPrompt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.imzmq.interview.agent.application.contract.AgentMessagingOperations;
import io.github.imzmq.interview.agent.application.contract.command.AgentMessageCommand;
import io.github.imzmq.interview.agent.application.contract.message.AgentMessage;
import io.github.imzmq.interview.agent.application.contract.message.AgentMessageIntent;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionMapper;
import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ChatContextCompressor {
    private static final Logger log = LoggerFactory.getLogger(ChatContextCompressor.class);
    private static final int COMPRESS_TRIGGER_THRESHOLD = 12;
    private static final int RECENT_VERBATIM_COUNT = 6;
    private static final int INTENT_RECENT_MESSAGE_COUNT = 4;

    private final ChatMessageMapper messageMapper;
    private final ChatSessionMapper sessionMapper;
    private final RoutingChatService routingChatService;
    private final PromptManager promptManager;
    private final AgentMessagingOperations agentMessaging;

    public ChatContextCompressor(ChatMessageMapper messageMapper,
                                 ChatSessionMapper sessionMapper,
                                 RoutingChatService routingChatService,
                                 PromptManager promptManager,
                                 AgentMessagingOperations agentMessaging) {
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
        this.routingChatService = routingChatService;
        this.promptManager = promptManager;
        this.agentMessaging = agentMessaging;
    }

    public String buildCompressedContext(String sessionId) {
        ChatSessionDO session = sessionMapper.selectOne(
                new LambdaQueryWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getSessionId, sessionId)
        );
        Long contextStartMsgId = session != null ? session.getSummaryUpToMsgId() : null;
        Long total = messageMapper.selectCount(
                new LambdaQueryWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getSessionId, sessionId)
                        .gt(contextStartMsgId != null, ChatMessageDO::getId, contextStartMsgId)
        );
        if (total == null || total <= COMPRESS_TRIGGER_THRESHOLD) {
            return buildVerbatimHistory(sessionId, 0, contextStartMsgId);
        }

        List<ChatMessageDO> recent = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getSessionId, sessionId)
                        .gt(contextStartMsgId != null, ChatMessageDO::getId, contextStartMsgId)
                        .orderByDesc(ChatMessageDO::getCreatedAt)
                        .last("LIMIT " + RECENT_VERBATIM_COUNT)
        );
        Collections.reverse(recent);
        String existingSummary = session != null ? session.getContextSummary() : null;
        boolean hasSummary = existingSummary != null && !existingSummary.isBlank();

        if (hasUncompressedMessages(sessionId, session, recent)) {
            triggerAsyncCompression(sessionId);
        }

        // 首次还没有摘要时，降级为取更多消息（避免上下文断崖）
        if (!hasSummary) {
            return buildVerbatimHistory(
                    sessionId, COMPRESS_TRIGGER_THRESHOLD + RECENT_VERBATIM_COUNT, contextStartMsgId);
        }

        String recentText = formatMessages(recent);
        String sessionContext = "【会话摘要】\n" + existingSummary + "\n\n【近期对话】\n" + recentText;
        return sessionContext;
    }

    public String buildIntentRoutingContext(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "";
        }
        ChatSessionDO session = sessionMapper.selectOne(
                new LambdaQueryWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getSessionId, sessionId)
        );
        Long contextStartMsgId = session != null ? session.getSummaryUpToMsgId() : null;
        List<ChatMessageDO> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getSessionId, sessionId)
                        .gt(contextStartMsgId != null, ChatMessageDO::getId, contextStartMsgId)
                        .orderByDesc(ChatMessageDO::getCreatedAt)
                        .last("LIMIT " + (INTENT_RECENT_MESSAGE_COUNT * 2))
        );
        Collections.reverse(messages);
        List<ChatMessageDO> filteredMessages = messages.stream()
                .filter(this::shouldIncludeForIntentRouting)
                .toList();
        List<ChatMessageDO> filtered = filteredMessages.stream()
                .skip(Math.max(0, filteredMessages.size() - INTENT_RECENT_MESSAGE_COUNT))
                .toList();
        return formatMessages(filtered);
    }

    @PostConstruct
    public void init() {
        agentMessaging.subscribe("ChatContextCompressor", this::handleCompression);
    }

    private String buildVerbatimHistory(String sessionId) {
        return buildVerbatimHistory(sessionId, 0, null);
    }

    private String buildVerbatimHistory(String sessionId, int limit) {
        return buildVerbatimHistory(sessionId, limit, null);
    }

    private String buildVerbatimHistory(String sessionId, int limit, Long contextStartMsgId) {
        LambdaQueryWrapper<ChatMessageDO> wrapper = new LambdaQueryWrapper<ChatMessageDO>()
                .eq(ChatMessageDO::getSessionId, sessionId)
                .gt(contextStartMsgId != null, ChatMessageDO::getId, contextStartMsgId)
                .orderByDesc(ChatMessageDO::getCreatedAt);
        if (limit > 0) {
            wrapper.last("LIMIT " + limit);
        }
        List<ChatMessageDO> messages = messageMapper.selectList(wrapper);
        Collections.reverse(messages);
        return formatMessages(messages);
    }

    private boolean hasUncompressedMessages(String sessionId, ChatSessionDO session, List<ChatMessageDO> recent) {
        if (recent.isEmpty() || recent.get(0).getId() == null) {
            return false;
        }
        Long oldestRecentId = recent.get(0).getId();
        LambdaQueryWrapper<ChatMessageDO> wrapper = new LambdaQueryWrapper<ChatMessageDO>()
                .eq(ChatMessageDO::getSessionId, sessionId)
                .lt(ChatMessageDO::getId, oldestRecentId);
        Long summaryUpToMsgId = session != null ? session.getSummaryUpToMsgId() : null;
        if (summaryUpToMsgId != null) {
            wrapper.gt(ChatMessageDO::getId, summaryUpToMsgId);
        }
        Long count = messageMapper.selectCount(wrapper);
        return count != null && count > 0;
    }

    private void triggerAsyncCompression(String sessionId) {
        agentMessaging.publish(new AgentMessageCommand(
                "ChatContextCompressor",
                "ChatContextCompressor",
                AgentMessageIntent.CHAT_CONTEXT_COMPRESS,
                Map.of("sessionId", sessionId),
                "chat-context-compress",
                "ChatContextCompressor",
                Map.of(),
                UUID.randomUUID().toString(),
                null
        ));
    }

    private void handleCompression(AgentMessage message) {
        if (message.intent() != AgentMessageIntent.CHAT_CONTEXT_COMPRESS) {
            return;
        }
        try {
            Map<String, Object> payload = message.payload();
            String sessionId = payload == null ? null : (String) payload.get("sessionId");
            if (sessionId == null || sessionId.isBlank()) {
                log.warn("收到无效上下文压缩任务，sessionId 为空");
                return;
            }

            ChatSessionDO session = sessionMapper.selectOne(
                    new LambdaQueryWrapper<ChatSessionDO>()
                            .eq(ChatSessionDO::getSessionId, sessionId)
            );
            if (session == null) {
                log.warn("会话不存在，忽略上下文压缩任务: {}", sessionId);
                return;
            }

            List<ChatMessageDO> recent = messageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessageDO>()
                            .eq(ChatMessageDO::getSessionId, sessionId)
                            .gt(session.getSummaryUpToMsgId() != null, ChatMessageDO::getId, session.getSummaryUpToMsgId())
                            .orderByDesc(ChatMessageDO::getCreatedAt)
                            .last("LIMIT " + RECENT_VERBATIM_COUNT)
            );
            if (recent.isEmpty()) {
                return;
            }
            Collections.reverse(recent);
            Long oldestRecentId = recent.get(0).getId();
            if (oldestRecentId == null) {
                return;
            }

            LambdaQueryWrapper<ChatMessageDO> candidateWrapper = new LambdaQueryWrapper<ChatMessageDO>()
                    .eq(ChatMessageDO::getSessionId, sessionId)
                    .lt(ChatMessageDO::getId, oldestRecentId)
                    .orderByAsc(ChatMessageDO::getCreatedAt);
            if (session.getSummaryUpToMsgId() != null) {
                candidateWrapper.gt(ChatMessageDO::getId, session.getSummaryUpToMsgId());
            }
            List<ChatMessageDO> toCompress = messageMapper.selectList(candidateWrapper);
            if (toCompress.isEmpty()) {
                return;
            }

            Map<String, Object> vars = new HashMap<>();
            vars.put("existingSummary", session.getContextSummary() == null ? "" : session.getContextSummary());
            vars.put("newMessages", formatMessages(toCompress));
            RenderedPrompt pair = promptManager.renderSplit("context-compressor", "chat-context-compress", vars);
            String newSummary = routingChatService.call(
                    pair.systemPrompt(), pair.userPrompt(), ModelRouteType.THINKING, "上下文压缩");

            Long newUpToMsgId = toCompress.get(toCompress.size() - 1).getId();
            sessionMapper.update(null,
                    new LambdaUpdateWrapper<ChatSessionDO>()
                            .eq(ChatSessionDO::getSessionId, sessionId)
                            .set(ChatSessionDO::getContextSummary, newSummary)
                            .set(ChatSessionDO::getSummaryUpToMsgId, newUpToMsgId)
            );

            log.info("上下文压缩完成: sessionId={}, summaryUpToMsgId={}", sessionId, newUpToMsgId);
        } catch (Exception e) {
            log.error("处理上下文压缩任务失败", e);
            throw new RuntimeException("Chat context compression failed", e);
        }
    }

    private String formatMessages(List<ChatMessageDO> messages) {
        return messages.stream()
                .map(m -> ("user".equals(m.getRole()) ? "User" : "AI") + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
    }

    private boolean shouldIncludeForIntentRouting(ChatMessageDO message) {
        if (message == null) {
            return false;
        }
        String content = message.getContent();
        if (content == null || content.isBlank()) {
            return false;
        }
        if ("正在生成中...".equals(content.trim())) {
            return false;
        }
        Map<String, Object> metadata = message.getMetadata();
        if (metadata == null) {
            return true;
        }
        Object generationStatus = metadata.get("generationStatus");
        return generationStatus == null || !"RUNNING".equalsIgnoreCase(String.valueOf(generationStatus));
    }
}






