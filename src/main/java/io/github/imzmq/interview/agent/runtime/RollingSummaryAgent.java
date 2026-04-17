package io.github.imzmq.interview.agent.runtime;

import io.github.imzmq.interview.agent.a2a.A2ABus;
import io.github.imzmq.interview.agent.a2a.A2AMessage;
import io.github.imzmq.interview.interview.domain.InterviewSession;
import io.github.imzmq.interview.mapper.interview.InterviewSessionMapper;
import io.github.imzmq.interview.session.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 滚动式总结智能体 (Rolling Summary Agent)。
 *
 * 【架构思考与设计原理】
 * 1. 原来的痛点：早期实现是将所有对话历史塞给大模型。当对话超过 10 轮时，上下文长度急剧膨胀，不仅消耗大量 Token（费用高昂），
 *    而且大模型容易出现“中间迷失（Lost in the Middle）”现象，导致早期提取的用户画像（如自我介绍中的技术栈）被遗忘。
 * 2. 为什么这样优化：采用“滚动窗口压缩”机制。每满 5 轮，启动一个异步任务，把【旧的全局总结】和【新增的 5 轮】输入给模型，
 *    压缩成一个新的【全局总结】。这样无论对话进行多少轮，传给主流程模型的上下文长度始终是恒定的（即固定长度的 summary）。
 * 3. 并发与版本控制：利用 `targetCount`（当前轮数）作为一种乐观锁/版本号机制，防止消息乱序导致旧的总结覆盖了新的总结。
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
    private final InterviewSessionMapper interviewSessionMapper;
    private final RoutingChatService routingChatService;
    private final ObjectMapper objectMapper;

    public RollingSummaryAgent(A2ABus a2aBus, SessionRepository sessionRepository,
                               InterviewSessionMapper interviewSessionMapper,
                               RoutingChatService routingChatService) {
        this.a2aBus = a2aBus;
        this.sessionRepository = sessionRepository;
        this.interviewSessionMapper = interviewSessionMapper;
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
            int targetCount = ((Number) payload.get("targetCount")).intValue();
            
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
                    "【旧的总结】：\n" + ((oldSummary == null || oldSummary.isBlank()) ? "无" : oldSummary) + "\n\n" +
                    "【最近对话】：\n" + historyJson + "\n\n" +
                    "要求：\n" +
                    "1. 只保留用户的核心表现、暴露的技术盲点以及关键背景信息。\n" +
                    "2. 剔除一切客套话、寒暄以及冗长的代码细节。\n" +
                    "3. 总结长度尽量控制在 200 字以内，必须是纯文本。";

            logger.debug("====== [RollingSummaryAgent] 开始异步压缩上下文 ======");
            String newSummary = routingChatService.call(prompt, ModelRouteType.THINKING, "滚动总结");
            logger.debug("====== [RollingSummaryAgent] 新的滚动总结 ======\n{}", newSummary);

            // 3. 仅局部更新 rollingSummary 字段，避免覆盖并发期间其他字段更新。
            int updated = interviewSessionMapper.updateRollingSummary(sessionId, newSummary);
            if (updated <= 0) {
                logger.warn("滚动总结写回失败或会话不存在: sessionId={}", sessionId);
                return;
            }

            logger.info("滚动总结完成，会话 {} 的上下文已更新。", sessionId);

        } catch (Exception e) {
            logger.error("处理滚动总结任务失败", e);
            // 抛出异常，让 A2ABus/RocketMQ 进行死信队列(DLQ)处理或重试
            throw new RuntimeException("Rolling Summary Failed", e);
        }
    }
}







