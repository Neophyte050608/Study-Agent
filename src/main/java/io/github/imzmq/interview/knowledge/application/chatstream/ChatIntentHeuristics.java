package io.github.imzmq.interview.knowledge.application.chatstream;

public final class ChatIntentHeuristics {

    private static final String[] EXPLICIT_MODE_SWITCH_PREFIXES = {
            "开始面试",
            "开启面试",
            "模拟面试",
            "来一场面试",
            "来场面试",
            "重新面试",
            "结束面试",
            "生成报告",
            "来一道",
            "来道",
            "出一道",
            "出道",
            "刷题",
            "来一题",
            "来几道",
            "来两道",
            "来三道",
            "换个题",
            "换一题",
            "学习计划",
            "学习画像",
            "编码练习"
    };

    private ChatIntentHeuristics() {
    }

    public static boolean looksLikeNewIntent(String content) {
        return looksLikeExplicitModeSwitch(content);
    }

    public static boolean looksLikeExplicitModeSwitch(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String s = content.trim();
        for (String prefix : EXPLICIT_MODE_SWITCH_PREFIXES) {
            if (s.startsWith(prefix)) {
                return true;
            }
        }
        return "停止".equals(s)
                || "停止生成".equals(s)
                || "结束".equals(s)
                || "退出".equals(s)
                || "生成报告".equals(s);
    }
}


