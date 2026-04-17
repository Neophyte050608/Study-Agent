package io.github.imzmq.interview.im.application.runtime;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ImCommandDispatcher {

    private static final Map<String, String> COMMAND_REPLIES = Map.of(
            "/clear", "上下文已清空。我们可以重新开始了！",
            "/help", "我是你的 AI 面试官助理。你可以尝试这样问我：\n"
                    + "1. 给我来一道 Java 相关的算法题\n"
                    + "2. 我们开启一场 Spring Boot 的模拟面试吧\n"
                    + "3. 帮我查询我的学习画像和计划"
    );

    public String dispatch(String content) {
        if (content == null) {
            return "";
        }
        return COMMAND_REPLIES.getOrDefault(content.trim(), "");
    }

    public boolean isClearCommand(String content) {
        return "/clear".equals(content);
    }
}


