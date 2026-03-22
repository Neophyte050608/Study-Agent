package com.example.interview.im.service;

import com.example.interview.agent.InterviewOrchestratorAgent;
import com.example.interview.agent.TaskRouterAgent;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.TaskType;
import com.example.interview.core.InterviewSession;
import com.example.interview.im.model.UnifiedMessage;
import com.example.interview.im.model.UnifiedReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Service
@RequiredArgsConstructor
@Slf4j
public class ImWebhookService {

    private final StringRedisTemplate redisTemplate;
    private final ImConversationStore conversationStore;
    private final TaskRouterAgent taskRouterAgent;
    private final FeishuReplyAdapter feishuReplyAdapter;
    
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
     * 异步处理 IM 消息，避免阻塞 Webhook 响应线程
     */
    public void dispatchMessageAsync(UnifiedMessage message) {
        CompletableFuture.runAsync(() -> {
            try {
                log.info("开始异步处理 IM 消息: [{}] {}", message.getSessionId(), message.getContent());
                
                String content = message.getContent();
                String sessionId = message.getSessionId();

                // 1. 特殊指令处理
                if ("/clear".equals(content)) {
                    conversationStore.clearSession(sessionId);
                    sendReply(message, "上下文已清空。我们可以重新开始了！");
                    return;
                } else if ("/help".equals(content)) {
                    sendReply(message, "我是你的 AI 面试官助理。你可以尝试这样问我：\n" +
                            "1. 给我来一道 Java 相关的算法题\n" +
                            "2. 我们开启一场 Spring Boot 的模拟面试吧\n" +
                            "3. 帮我查询我的学习画像和计划");
                    return;
                }

                // 2. 更新并获取会话历史（用于增强 LLM 理解上下文的能力）
                conversationStore.addMessage(sessionId, "User", content);
                String history = conversationStore.getHistoryContext(sessionId);
                String activeInterviewId = conversationStore.getActiveSession(sessionId);

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
                    replyText = formatResponse(response);
                    // 将 AI 的回复也存入历史，保持对话连续性
                    conversationStore.addMessage(sessionId, "AI", replyText);
                    
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
        
        if ("feishu".equals(message.getPlatform())) {
            feishuReplyAdapter.sendReply(reply);
        }
    }

    /**
     * 将 TaskResponse 转换为用户可读的纯净文本格式。
     * 隐藏技术元数据（如 agent, sessionId），仅返回核心业务内容。
     */
    private String formatResponse(TaskResponse response) {
        Object data = response.data();
        if (data == null) {
            return response.message() != null ? response.message() : "任务执行完毕。";
        }
        
        StringBuilder sb = new StringBuilder();

        // 1. 处理面试会话开始 (InterviewSession)
        if (data instanceof InterviewSession session) {
            return session.getCurrentQuestion();
        }

        // 2. 处理答题结果 (AnswerResult)
        if (data instanceof InterviewOrchestratorAgent.AnswerResult result) {
            sb.append("【评分】").append(result.score()).append("\n\n");
            sb.append("【反馈】\n").append(result.feedback()).append("\n\n");
            if (result.finished()) {
                sb.append("恭喜！本次模拟面试已结束。你可以发送“生成报告”来查看最终评估。");
            } else {
                sb.append("【下一题】\n").append(result.nextQuestion());
            }
            return sb.toString();
        }

        // 3. 处理最终报告 (FinalReport)
        if (data instanceof InterviewOrchestratorAgent.FinalReport report) {
            sb.append("====== 面试复盘报告 ======\n\n");
            sb.append("【总评】\n").append(report.summary()).append("\n\n");
            sb.append("【薄弱环节】\n").append(report.weak()).append("\n\n");
            sb.append("【错误点】\n").append(report.wrong()).append("\n\n");
            sb.append("【平均分】").append(String.format("%.1f", report.averageScore())).append("\n\n");
            sb.append("【后续建议】\n").append(report.nextFocus());
            return sb.toString();
        }

        // 4. 处理通用 Map 结构（如刷题、画像查询等）
        if (data instanceof Map<?, ?> map) {
            // 优先提取核心业务字段，避免展示全部元数据
            if (map.containsKey("question")) {
                return String.valueOf(map.get("question"));
            }
            if (map.containsKey("recommendation")) {
                return String.valueOf(map.get("recommendation"));
            }
            if (map.containsKey("summary")) {
                return String.valueOf(map.get("summary"));
            }

            // 如果没有核心字段，则有选择地展示非内部字段
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                // 排除常见的技术元数据
                if (List.of("agent", "sessionId", "userId", "status", "traceId", "profileSnapshotApplied").contains(key)) {
                    continue;
                }
                sb.append("• ").append(key).append(": ").append(entry.getValue()).append("\n");
            }
            
            String result = sb.toString().trim();
            return result.isEmpty() ? "处理成功。" : result;
        }

        // 5. 兜底处理
        return String.valueOf(data);
    }
}
