package com.example.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.interview.agent.a2a.A2ABus;
import com.example.interview.agent.a2a.A2AIntent;
import com.example.interview.agent.a2a.A2AMessage;
import com.example.interview.entity.ChatSessionDO;
import com.example.interview.entity.UserChatMemoryDO;
import com.example.interview.mapper.ChatSessionMapper;
import com.example.interview.mapper.UserChatMemoryMapper;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.RoutingChatService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class ChatMemoryExtractorAgent {
    private static final Logger log = LoggerFactory.getLogger(ChatMemoryExtractorAgent.class);

    private final UserChatMemoryMapper memoryMapper;
    private final ChatSessionMapper sessionMapper;
    private final RoutingChatService routingChatService;
    private final PromptManager promptManager;
    private final A2ABus a2aBus;

    public ChatMemoryExtractorAgent(UserChatMemoryMapper memoryMapper,
                                    ChatSessionMapper sessionMapper,
                                    RoutingChatService routingChatService,
                                    PromptManager promptManager,
                                    A2ABus a2aBus) {
        this.memoryMapper = memoryMapper;
        this.sessionMapper = sessionMapper;
        this.routingChatService = routingChatService;
        this.promptManager = promptManager;
        this.a2aBus = a2aBus;
    }

    @PostConstruct
    public void init() {
        a2aBus.subscribe("ChatMemoryExtractorAgent", this::handleMemorize);
    }

    private void handleMemorize(A2AMessage message) {
        if (message.intent() != A2AIntent.CROSS_SESSION_MEMORIZE) {
            return;
        }
        try {
            Map<String, Object> payload = message.payload();
            String sessionId = payload == null ? null : (String) payload.get("sessionId");
            if (sessionId == null || sessionId.isBlank()) {
                log.warn("收到无效跨会话记忆任务，sessionId 为空");
                return;
            }

            ChatSessionDO session = sessionMapper.selectOne(
                    new LambdaQueryWrapper<ChatSessionDO>()
                            .eq(ChatSessionDO::getSessionId, sessionId)
            );
            if (session == null) {
                log.warn("会话不存在，忽略记忆提取: {}", sessionId);
                return;
            }

            String summary = session.getContextSummary();
            if (summary == null || summary.length() < 20) {
                log.debug("会话摘要太短，跳过记忆提取: sessionId={}", sessionId);
                return;
            }

            String userId = session.getUserId();
            UserChatMemoryDO existingMemory = memoryMapper.selectOne(
                    new LambdaQueryWrapper<UserChatMemoryDO>()
                            .eq(UserChatMemoryDO::getUserId, userId)
            );
            if (existingMemory != null && sessionId.equals(existingMemory.getLastSessionId())) {
                log.debug("会话记忆已提取过，跳过: sessionId={}", sessionId);
                return;
            }

            Map<String, Object> vars = new HashMap<>();
            vars.put("existingMemory", existingMemory != null ? existingMemory.getMemoryText() : "");
            vars.put("sessionSummary", summary);
            vars.put("sessionTitle", session.getTitle() != null ? session.getTitle() : "无标题");
            String prompt = promptManager.render("cross-session-memorize", vars);

            String newMemory = routingChatService.call(prompt, ModelRouteType.THINKING, "跨会话记忆提取");

            if (existingMemory != null) {
                memoryMapper.update(null,
                        new LambdaUpdateWrapper<UserChatMemoryDO>()
                                .eq(UserChatMemoryDO::getUserId, userId)
                                .set(UserChatMemoryDO::getMemoryText, newMemory)
                                .set(UserChatMemoryDO::getLastSessionId, sessionId)
                );
            } else {
                UserChatMemoryDO newRecord = new UserChatMemoryDO();
                newRecord.setUserId(userId);
                newRecord.setMemoryText(newMemory);
                newRecord.setLastSessionId(sessionId);
                memoryMapper.insert(newRecord);
            }
            log.info("跨会话记忆提取完成: userId={}, sessionId={}", userId, sessionId);
        } catch (Exception e) {
            log.error("处理跨会话记忆提取任务失败", e);
            throw new RuntimeException("Cross-session memory extraction failed", e);
        }
    }
}
