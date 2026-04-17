package io.github.imzmq.interview.interview.domain.statemachine;

import com.alibaba.cola.statemachine.Action;
import com.alibaba.cola.statemachine.Condition;
import com.alibaba.cola.statemachine.StateMachine;
import com.alibaba.cola.statemachine.builder.StateMachineBuilder;
import com.alibaba.cola.statemachine.builder.StateMachineBuilderFactory;
import io.github.imzmq.interview.interview.domain.InterviewStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 基于阿里 COLA Statemachine 的面试 SOP 状态机配置。
 * 
 * 核心优势：
 * 无状态设计（Stateless），状态机本身不保存会话状态，只定义规则。
 * 非常适合 Web 请求的并发场景（从 DB 获取状态 -> 发送事件过状态机 -> 新状态存入 DB）。
 */
@Configuration
public class InterviewStateMachineConfig {

    private static final Logger logger = LoggerFactory.getLogger(InterviewStateMachineConfig.class);
    
    public static final String MACHINE_ID = "interviewStateMachine";

    @Bean
    public StateMachine<InterviewStage, InterviewEvent, InterviewContext> interviewStateMachine() {
        StateMachineBuilder<InterviewStage, InterviewEvent, InterviewContext> builder = StateMachineBuilderFactory.create();

        // 1. 从 自我介绍 -> 简历深挖
        builder.externalTransition()
                .from(InterviewStage.INTRODUCTION)
                .to(InterviewStage.RESUME_DEEP_DIVE)
                .on(InterviewEvent.FINISH_INTRO)
                .when(checkIntroFinished())
                .perform(logTransition());

        // 2. 从 简历深挖 -> 核心八股
        builder.externalTransition()
                .from(InterviewStage.RESUME_DEEP_DIVE)
                .to(InterviewStage.CORE_KNOWLEDGE)
                .on(InterviewEvent.COMPLETE_RESUME_DIVE)
                .when(checkResumeDiveCompleted())
                .perform(logTransition());

        // 3. 从 核心八股 -> 算法手撕
        builder.externalTransition()
                .from(InterviewStage.CORE_KNOWLEDGE)
                .to(InterviewStage.SCENARIO_OR_CODING)
                .on(InterviewEvent.COMPLETE_CORE_KNOWLEDGE)
                .when(checkCoreKnowledgeCompleted())
                .perform(logTransition());

        // 4. 从 算法手撕 -> 反问环节
        builder.externalTransition()
                .from(InterviewStage.SCENARIO_OR_CODING)
                .to(InterviewStage.CLOSING)
                .on(InterviewEvent.COMPLETE_CODING)
                .when(checkAlwaysTrue()) // 编码完成即可进入收尾
                .perform(logTransition());

        return builder.build(MACHINE_ID);
    }

    // --- Conditions (状态流转前置条件校验) ---

    public static boolean isReadyForResumeDive(int historySize, int totalQuestions) {
        return historySize >= 1;
    }

    public static boolean isReadyForCoreKnowledge(int historySize, int totalQuestions) {
        int remaining = Math.max(0, totalQuestions - 2);
        int resumeCount = Math.max(1, (int)(remaining * 0.3));
        return historySize >= 1 + resumeCount;
    }

    public static boolean isReadyForCoding(int historySize, int totalQuestions) {
        int remaining = Math.max(0, totalQuestions - 2);
        int resumeCount = Math.max(1, (int)(remaining * 0.3));
        int coreCount = Math.max(1, (int)(remaining * 0.4));
        return historySize >= 1 + resumeCount + coreCount;
    }

    public static boolean isReadyForClosing(int historySize, int totalQuestions) {
        return historySize >= Math.max(1, totalQuestions - 1);
    }

    private Condition<InterviewContext> checkIntroFinished() {
        return context -> isReadyForResumeDive(context.getSession().getHistory().size(), context.getSession().getTotalQuestions());
    }

    private Condition<InterviewContext> checkResumeDiveCompleted() {
        return context -> isReadyForCoreKnowledge(context.getSession().getHistory().size(), context.getSession().getTotalQuestions());
    }

    private Condition<InterviewContext> checkCoreKnowledgeCompleted() {
        return context -> isReadyForCoding(context.getSession().getHistory().size(), context.getSession().getTotalQuestions());
    }

    private Condition<InterviewContext> checkAlwaysTrue() {
        return context -> isReadyForClosing(context.getSession().getHistory().size(), context.getSession().getTotalQuestions());
    }

    // --- Actions (状态流转时执行的动作) ---

    private Action<InterviewStage, InterviewEvent, InterviewContext> logTransition() {
        return (from, to, event, context) -> {
            logger.info("====== [COLA State Machine] 面试 SOP 流转触发 ======");
            logger.info("Session ID: {}", context.getSession().getId());
            logger.info("Event: {}", event);
            logger.info("Transition: {} -> {}", from.name(), to.name());
        };
    }
}




