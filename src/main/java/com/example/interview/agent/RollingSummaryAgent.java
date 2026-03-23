package com.example.interview.agent;

import com.example.interview.agent.a2a.A2ABus;
import com.example.interview.agent.a2a.A2AMessage;
import com.example.interview.core.InterviewSession;
import com.example.interview.session.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.RoutingChatService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 滚动式总结智能体 (Rolling Summary Agent)。
 *
 * 核心职责：
 * 作为 RocketMQ 消费者，监听并处理长对话场景下的异步总结任务。
 * 它利用大模型将“旧总结 + 新增的 5 轮对话”重新压缩为精简的 Profile，
 * 并在数据库中利用“对话轮数”作为版本号来防止并发更新和乱序问题。
 */
@Component
public class RollingSummaryAgent {

    private static final Logger logger = LoggerFactory.getLogger(RollingSummaryAgent.class);

    private final A2ABus a2aBus;
    private final SessionRepository sessionRepository;
    private final RoutingChatService routingChatService;
    private final ObjectMapper objectMapper;

    public RollingSummaryAgent(A2ABus a2aBus, SessionRepository sessionRepository, 
                               RoutingChatService routingChatService) {
        this.a2aBus = a2aBus;
        this.sessionRepository = sessionRepository;
        this.routingChatService = routingChatService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 在系统启动时向 A2A 总线注册自己。
     * 当有 intent 为 ROLLING_SUMMARY 或 receiver 为 RollingSummaryAgent 的消息时，会路由到这里。
     */
    @PostConstruct
    public void init() {
        a2aBus.subscribe("RollingSummaryAgent", this::handleSummaryTask);
    }

    private void handleSummaryTask(A2AMessage message) {
        try {
            Map<String, Object> payload = message.payload();
            String sessionId = (String) payload.get("sessionId");
            int targetCount = (Integer) payload.get("targetCount");
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> recentHistory = (List<Map<String, Object>>) payload.get("recentHistory");

            logger.info("收到滚动总结任务: sessionId={}, 目标轮数={}", sessionId, targetCount);

            // 1. 获取会话与版本校验
            InterviewSession session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null) {
                logger.warn("会话不存在，丢弃总结任务: {}", sessionId);
                return;
            }

            // [版本控制机制]：目前设计中，targetCount 就代表了本次总结覆盖到了第几轮
            // 如果系统中有另外的 profileVersion 字段，可以在此处比对：if (targetCount <= session.getProfileVersion()) return;
            
            // 2. 组装 Prompt 调用大模型进行压缩
            String oldSummary = session.getRollingSummary();
            String historyJson = objectMapper.writeValueAsString(recentHistory);
            
            String prompt = "你是一个会话上下文压缩器。\n" +
                    "请根据【旧的总结】和【最近的5轮对话】，生成一个全新的、精简的【全局总结】。\n\n" +
                    "【旧的总结】：\n" + (oldSummary.isBlank() ? "无" : oldSummary) + "\n\n" +
                    "【最近对话】：\n" + historyJson + "\n\n" +
                    "要求：\n" +
                    "1. 只保留用户的核心表现、暴露的技术盲点以及关键背景信息。\n" +
                    "2. 剔除一切客套话、寒暄以及冗长的代码细节。\n" +
                    "3. 总结长度尽量控制在 200 字以内，必须是纯文本。";

            System.out.println("====== [RollingSummaryAgent] 开始异步压缩上下文 ======");
            String newSummary = routingChatService.call(prompt, ModelRouteType.THINKING, "滚动总结");
            System.out.println("====== [RollingSummaryAgent] 新的滚动总结 ======\n" + newSummary);

            // 3. 更新会话状态并持久化
            // 注意：在实际生产的高并发环境下，这里最好使用带乐观锁的 SQL 更新（如 UPDATE ... WHERE version < targetCount）
            session.setRollingSummary(newSummary);
            sessionRepository.save(session);
            
            logger.info("滚动总结完成，会话 {} 的上下文已更新。", sessionId);

        } catch (Exception e) {
            logger.error("处理滚动总结任务失败", e);
            // 抛出异常，让 A2ABus/RocketMQ 进行死信队列(DLQ)处理或重试
            throw new RuntimeException("Rolling Summary Failed", e);
        }
    }
}
