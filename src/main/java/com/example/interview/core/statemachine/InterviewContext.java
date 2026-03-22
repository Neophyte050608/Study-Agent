package com.example.interview.core.statemachine;

import com.example.interview.core.InterviewSession;

/**
 * 传递给状态机的上下文环境，包含当前会话的全部信息，
 * 供状态转移条件（Condition）或执行动作（Action）使用。
 */
public class InterviewContext {
    private final InterviewSession session;
    private final String lastUserAnswer;
    private final double lastEvaluationScore;

    public InterviewContext(InterviewSession session, String lastUserAnswer, double lastEvaluationScore) {
        this.session = session;
        this.lastUserAnswer = lastUserAnswer;
        this.lastEvaluationScore = lastEvaluationScore;
    }

    public InterviewSession getSession() {
        return session;
    }

    public String getLastUserAnswer() {
        return lastUserAnswer;
    }

    public double getLastEvaluationScore() {
        return lastEvaluationScore;
    }
}