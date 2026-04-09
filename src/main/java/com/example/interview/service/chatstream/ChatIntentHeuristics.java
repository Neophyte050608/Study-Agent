package com.example.interview.service.chatstream;

public final class ChatIntentHeuristics {

    private ChatIntentHeuristics() {
    }

    public static boolean looksLikeNewIntent(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String s = content.trim();
        return s.contains("来一") || s.contains("来两") || s.contains("来几")
                || s.contains("来道") || s.contains("出一") || s.contains("出道")
                || s.contains("刷题") || s.contains("选择题") || s.contains("填空题") || s.contains("算法题") || s.contains("场景题")
                || s.contains("开始面试") || s.contains("开启面试") || s.contains("模拟面试")
                || s.contains("来一场") || s.contains("换个") || s.contains("结束面试")
                || s.contains("生成报告") || s.contains("学习计划") || s.contains("学习画像")
                || s.contains("编码练习") || s.contains("停止");
    }
}
