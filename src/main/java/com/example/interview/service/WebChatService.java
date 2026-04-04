package com.example.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.interview.agent.InterviewOrchestratorAgent;
import com.example.interview.agent.TaskRouterAgent;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.core.InterviewSession;
import com.example.interview.entity.ChatMessageDO;
import com.example.interview.entity.ChatSessionDO;
import com.example.interview.mapper.ChatMessageMapper;
import com.example.interview.mapper.ChatSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    public WebChatService(ChatSessionMapper sessionMapper,
                          ChatMessageMapper messageMapper,
                          TaskRouterAgent taskRouterAgent,
                          ChatContextCompressor chatContextCompressor) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.taskRouterAgent = taskRouterAgent;
        this.chatContextCompressor = chatContextCompressor;
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

    // ======== Chat Dispatch ========

    public TaskResponse dispatchChat(String sessionId, String userId, String userContent) {
        saveUserMessage(sessionId, userContent);
        String history = buildHistoryContext(sessionId);
        String traceId = UUID.randomUUID().toString();

        Map<String, Object> payload = new HashMap<>();
        payload.put("query", userContent);

        Map<String, Object> context = new HashMap<>();
        context.put("sessionId", sessionId);
        context.put("userId", userId);
        context.put("history", history);
        context.put("traceId", traceId);

        TaskRequest request = new TaskRequest(null, payload, context);
        TaskResponse response = taskRouterAgent.dispatch(request);

        String replyText = extractReplyText(response);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("traceId", traceId);
        saveAssistantMessage(sessionId, replyText, metadata);
        autoTitleIfNeeded(sessionId, userContent);
        return response;
    }

    // ======== Public helpers (used by ChatStreamingService) ========

    public String buildHistoryContext(String sessionId) {
        return chatContextCompressor.buildCompressedContext(sessionId);
    }

    public String extractReplyText(TaskResponse response) {
        if (!response.success()) {
            return "抱歉，处理您的请求时遇到了问题：" + response.message();
        }
        Object data = response.data();
        if (data == null) {
            return response.message() != null ? response.message() : "处理完毕。";
        }
        if (data instanceof InterviewSession session) {
            return session.getCurrentQuestion();
        }
        if (data instanceof InterviewOrchestratorAgent.AnswerResult result) {
            StringBuilder sb = new StringBuilder();
            sb.append("【评分】").append(result.score()).append("\n\n");
            sb.append("【反馈】\n").append(result.feedback()).append("\n\n");
            if (result.finished()) {
                sb.append("本次模拟面试已结束。你可以说\"生成报告\"查看最终评估。");
            } else {
                sb.append("【下一题】\n").append(result.nextQuestion());
            }
            return sb.toString();
        }
        if (data instanceof InterviewOrchestratorAgent.FinalReport report) {
            StringBuilder sb = new StringBuilder();
            sb.append("====== 面试复盘报告 ======\n\n");
            sb.append("【总评】\n").append(report.summary()).append("\n\n");
            sb.append("【薄弱环节】\n").append(report.weak()).append("\n\n");
            sb.append("【错误点】\n").append(report.wrong()).append("\n\n");
            sb.append("【后续建议】\n").append(report.nextFocus());
            return sb.toString();
        }
        if (data instanceof Map<?, ?> map) {
            // 编码练习结果：专门格式化
            if ("CodingPracticeAgent".equals(map.get("agent"))) {
                return formatCodingResult(map);
            }
            if (map.containsKey("answer")) return String.valueOf(map.get("answer"));
            if (map.containsKey("question")) return String.valueOf(map.get("question"));
            if (map.containsKey("recommendation")) return String.valueOf(map.get("recommendation"));
            if (map.containsKey("summary")) return String.valueOf(map.get("summary"));
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (List.of("agent", "sessionId", "userId", "status", "traceId").contains(key)) continue;
                sb.append("• ").append(key).append(": ").append(entry.getValue()).append("\n");
            }
            String result = sb.toString().trim();
            return result.isEmpty() ? "处理成功。" : result;
        }
        return String.valueOf(data);
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

    /**
     * 格式化编码练习返回的 Map 结果，区分出题和评估两种场景。
     */
    private String formatCodingResult(Map<?, ?> map) {
        Object statusObj = map.get("status");
        String status = statusObj == null ? "" : String.valueOf(statusObj);
        StringBuilder sb = new StringBuilder();

        if ("evaluated".equals(status)) {
            // 答题评估结果
            Object score = map.get("score");
            Object feedback = map.get("feedback");
            Object nextHint = map.get("nextHint");
            Object progress = map.get("progress");
            if (score != null) sb.append("【得分】").append(score).append("\n\n");
            if (feedback != null && !String.valueOf(feedback).isBlank()) {
                sb.append("【解析与反馈】\n").append(feedback).append("\n\n");
            }
            if (nextHint != null && !String.valueOf(nextHint).isBlank()) {
                sb.append("【提示】").append(nextHint).append("\n\n");
            }
            if (Boolean.TRUE.equals(map.get("isLast"))) {
                sb.append("本轮练习已完成！");
            } else if (progress != null) {
                sb.append("进度：").append(progress);
            }
        } else if ("question_generated".equals(status) || "started".equals(status)) {
            // 出题结果
            Object question = map.get("question");
            Object progress = map.get("progress");
            if (question != null) sb.append(question);
            if (progress != null) sb.append("\n\n(进度：").append(progress).append(")");
        } else if ("completed".equals(status)) {
            Object message = map.get("message");
            sb.append(message != null ? message : "练习已完成！");
        } else {
            // fallback: 取 question 或 message
            Object question = map.get("question");
            Object message = map.get("message");
            if (question != null) return String.valueOf(question);
            if (message != null) return String.valueOf(message);
            return "处理完毕。";
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? "处理完毕。" : result;
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
