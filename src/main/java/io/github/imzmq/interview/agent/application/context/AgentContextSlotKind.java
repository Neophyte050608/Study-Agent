package io.github.imzmq.interview.agent.application.context;

public enum AgentContextSlotKind {
    PROFILE("用户画像", 2),
    SESSION_HISTORY("会话历史", 5),
    KNOWLEDGE("知识上下文", 3),
    DIALOG_SIGNAL("对话信号", 1),
    CONSTRAINTS("硬性约束", 0),
    TOOL_STATE("工具状态", 4),
    TASK_PLAN("任务规划", 2),
    TASK_MEMORY("任务记忆", 4),
    RECALL("相关回忆", 6);

    private final String title;
    private final int trimPriority;

    AgentContextSlotKind(String title, int trimPriority) {
        this.title = title;
        this.trimPriority = trimPriority;
    }

    public String title() {
        return title;
    }

    public int trimPriority() {
        return trimPriority;
    }
}
