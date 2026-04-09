package com.example.interview.im.service;

import com.example.interview.agent.InterviewOrchestratorAgent;
import com.example.interview.agent.TaskRouterAgent;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.TaskType;
import com.example.interview.core.InterviewSession;
import com.example.interview.im.model.UnifiedMessage;
import com.example.interview.im.model.UnifiedReply;
import com.example.interview.im.runtime.ClarificationResolver;
import com.example.interview.im.runtime.ImCommandDispatcher;
import com.example.interview.im.runtime.ImPlatformAdapterRegistry;
import com.example.interview.im.runtime.ImResponsePresenter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * IM 消息统一处理服务 (ImWebhookService)
 * 负责接收解析后的 IM 消息，维护会话上下文，并异步分发给任务路由中心 (TaskRouterAgent)。
 */
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ImWebhookService {

    private static final Logger log = LoggerFactory.getLogger(ImWebhookService.class);

    private final StringRedisTemplate redisTemplate;
    private final ImConversationStore conversationStore;
    private final TaskRouterAgent taskRouterAgent;
    private final ImPlatformAdapterRegistry imPlatformAdapterRegistry;
    private final ImCommandDispatcher imCommandDispatcher;
    private final ClarificationResolver clarificationResolver;
    private final ImResponsePresenter imResponsePresenter;

    public ImWebhookService(
            StringRedisTemplate redisTemplate,
            ImConversationStore conversationStore,
            TaskRouterAgent taskRouterAgent,
            ImPlatformAdapterRegistry imPlatformAdapterRegistry,
            ImCommandDispatcher imCommandDispatcher,
            ClarificationResolver clarificationResolver,
            ImResponsePresenter imResponsePresenter
    ) {
        this.redisTemplate = redisTemplate;
        this.conversationStore = conversationStore;
        this.taskRouterAgent = taskRouterAgent;
        this.imPlatformAdapterRegistry = imPlatformAdapterRegistry;
        this.imCommandDispatcher = imCommandDispatcher;
        this.clarificationResolver = clarificationResolver;
        this.imResponsePresenter = imResponsePresenter;
    }
    
    /** 事件幂等性缓存前缀 */
    private static final String EVENT_IDEMPOTENCY_PREFIX = "im:event:idempotency:";

    /**
     * 判断事件是否重复处理（幂等校验）
     */
    public boolean isDuplicateEvent(String eventId) {
        String key = EVENT_IDEMPOTENCY_PREFIX + eventId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 记录事件处理状态，防止短时间内重复推送
     */
    public void recordEvent(String eventId) {
        String key = EVENT_IDEMPOTENCY_PREFIX + eventId;
        // 缓存 1 小时即可
        redisTemplate.opsForValue().set(key, "1", 1, TimeUnit.HOURS);
    }

    /**
     * 原子写入事件处理标记。
     * 成功返回 true，表示当前调用方获得处理权；返回 false 表示该事件已被其他节点或线程记录。
     */
    public boolean tryRecordEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        String key = EVENT_IDEMPOTENCY_PREFIX + eventId;
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", 1, TimeUnit.HOURS));
    }

    /**
     * 异步处理 IM 消息，避免阻塞 Webhook 响应线程
     */
    public void dispatchMessageAsync(UnifiedMessage message) {
        CompletableFuture.runAsync(() -> {
            try {
                log.info("开始异步处理 IM 消息: [{}] {}", message.getSessionId(), message.getContent());
                
                String content = message.getContent();
                String sessionId = message.getSessionId();

                String commandReply = imCommandDispatcher.dispatch(content);
                if (!commandReply.isBlank()) {
                    if (imCommandDispatcher.isClearCommand(content)) {
                        conversationStore.clearSession(sessionId);
                    }
                    sendReply(message, commandReply);
                    return;
                }

                // 2. 更新并获取会话历史（用于增强 LLM 理解上下文的能力）
                conversationStore.addMessage(sessionId, "User", content);
                String history = conversationStore.getHistoryContext(sessionId);
                String activeInterviewId = conversationStore.getActiveSession(sessionId);
                String pendingClarification = conversationStore.getPendingClarification(sessionId);

                // 3. 构建统一任务请求 (TaskRequest)
                // 如果当前有活跃的面试会话，优先判定为回答面试问题
                TaskType forcedTaskType = null;
                Map<String, Object> payload = new HashMap<>();
                payload.put("query", content);

                if (activeInterviewId != null && !activeInterviewId.isBlank()) {
                    // 如果用户没有显式要求切换模式（如 /help, /clear 等已处理），则默认为回答上一题
                    forcedTaskType = TaskType.INTERVIEW_ANSWER;
                    payload.put("sessionId", activeInterviewId);
                    payload.put("userAnswer", content);
                } else if (pendingClarification != null && !pendingClarification.isBlank()) {
                    TaskType clarifiedTaskType = clarificationResolver.resolveTaskType(pendingClarification, content);
                    if (clarifiedTaskType != null) {
                        forcedTaskType = clarifiedTaskType;
                        payload.putAll(clarificationResolver.buildPayload(clarifiedTaskType, content, pendingClarification));
                        conversationStore.clearPendingClarification(sessionId);
                    }
                }
                
                Map<String, Object> context = new HashMap<>();
                context.put("sessionId", sessionId);
                context.put("userId", message.getSenderId());
                context.put("history", history);
                context.put("traceId", UUID.randomUUID().toString());

                TaskRequest request = new TaskRequest(forcedTaskType, payload, context);

                // 4. 调用任务路由器进行分发处理
                TaskResponse response = taskRouterAgent.dispatch(request);

                // 5. 格式化执行结果并异步回复给用户
                String replyText;
                if (response.success()) {
                    replyText = imResponsePresenter.format(response);
                    // 将 AI 的回复也存入历史，保持对话连续性
                    conversationStore.addMessage(sessionId, "AI", replyText);
                    clarificationResolver.captureState(sessionId, response, conversationStore);
                    
                    // 如果是新开启的面试会话，记录 activeSessionId
                    if (response.data() instanceof InterviewSession session) {
                        conversationStore.setActiveSession(sessionId, session.getId());
                    }
                    // 如果面试已结束，清除 activeSessionId
                    if (response.data() instanceof InterviewOrchestratorAgent.AnswerResult result && result.finished()) {
                        conversationStore.clearSession(sessionId); // 这里也可以只清 activeSession
                    }
                } else {
                    replyText = "抱歉，处理您的请求时遇到了问题：" + response.message();
                }

                sendReply(message, replyText);
                
            } catch (Exception e) {
                log.error("异步处理 IM 消息过程中发生异常", e);
                sendReply(message, "抱歉，系统内部发生错误，请稍后再试。");
            }
        });
    }

    /**
     * 调用飞书适配器发送消息回复
     */
    private void sendReply(UnifiedMessage message, String text) {
        UnifiedReply reply = UnifiedReply.builder()
                .platform(message.getPlatform())
                .sessionId(message.getSessionId())
                .replyToMessageId(message.getMessageId())
                .text(text)
                .traceId(UUID.randomUUID().toString())
                .build();
        imPlatformAdapterRegistry.sendReply(reply);
    }

}
