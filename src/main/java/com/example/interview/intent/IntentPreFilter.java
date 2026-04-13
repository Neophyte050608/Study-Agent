package com.example.interview.intent;

import com.example.interview.observability.TraceNode;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性规则前置过滤器（Layer 1）。
 * 在调用 LLM 意图分类之前，通过关键词和正则快速截获可以确定的意图。
 */
@Component
public class IntentPreFilter {

    private static final String HELP_REPLY =
            "我是你的 AI 面试官助理。你可以尝试这样和我对话：\n" +
            "1. 给我来一道 Java 相关的算法题\n" +
            "2. 我们开启一场 Spring Boot 的模拟面试吧\n" +
            "3. 帮我查询我的学习画像和计划\n" +
            "4. 什么是 Redis 的持久化机制？（知识问答）\n" +
            "输入 /clear 可以清空当前对话上下文。";

    private static final String CLEAR_REPLY = "上下文已清空。我们可以重新开始了！";

    private static final String GREETING_REPLY =
            "你好！我是你的 AI 面试官助理。你可以：\n" +
            "- 说\"开始一场 Java 面试\"来进行模拟面试\n" +
            "- 说\"来一道算法题\"来刷题练习\n" +
            "- 说\"查询学习计划\"来查看学习画像\n" +
            "- 直接问我任何技术问题";

    private static final String[] GREETINGS = {
            "你好", "您好", "hi", "hello", "hey", "嗨", "哈喽",
            "早上好", "上午好", "中午好", "下午好", "晚上好", "晚好",
            "早安", "午安", "晚安", "在吗", "在不在"
    };

    private static final String[] INTERVIEW_START_PREFIXES = {
            "开始面试", "开启面试", "模拟面试", "来一场面试", "来场面试",
            "开始一场", "开启一场", "来一场", "进行一场",
            "重新面试"
    };

    private static final Set<String> SHORT_INTERVIEW_PREFIXES = Set.of(
            "开始一场", "开启一场", "来一场", "进行一场"
    );

    private static final String[] REPORT_KEYWORDS = {
            "生成报告", "面试总结", "面试报告", "给我报告", "出报告"
    };

    private static final String[] CODING_PRACTICE_PREFIXES = {
            "来一道", "来道", "出一道", "出道",
            "刷题", "来一题", "来几道", "来两道", "来三道",
            "来1道", "来2道", "来3道",
            "换个题", "换一题", "再来一道", "再来一题",
            "编码练习"
    };

    private static final String[] PROFILE_KEYWORDS = {
            "学习计划", "学习画像", "查询画像", "查看画像",
            "我的计划", "训练计划"
    };

    // ===== 域级检测常量（Layer 1 域裁决） =====

    private static final Pattern KNOWLEDGE_QUESTION_PATTERN = Pattern.compile(
            "(?:^|[，,。.！!？?\\s])(?:请问|请教)?\\s*(?:什么是|如何|怎么|为什么|为啥|讲讲|说说|介绍一?下?|解释一?下?|谈谈|分析)" +
            "|(?:的?(?:原理|机制|区别|差异|优缺点|底层|实现|概念|含义|定义|作用|特点|特性))\\s*[？?。.]*$" +
            "|和.*(?:的?(?:区别|差异|对比|比较|不同))"
    );

    private static final String[] INTERVIEW_DOMAIN_KEYWORDS = {
            "面试", "面我", "面一面", "面一下", "mock", "模拟面试",
            "考我", "考考我", "面试报告", "面试总结", "复盘"
    };

    private static final String[] CODING_DOMAIN_KEYWORDS = {
            "题", "刷", "做题", "练习", "训练", "编程", "编码",
            "算法", "选择题", "填空", "场景题"
    };

    private static final String[] PROFILE_DOMAIN_KEYWORDS = {
            "学习计划", "画像", "薄弱", "弱项", "水平", "掌握",
            "推荐学", "能力分析", "进度"
    };

    private static final Pattern TOPIC_PATTERN = Pattern.compile(
            "(?:来|出|刷)\\s*[一二三四五六七八九十两\\d]*\\s*[道题个]?\\s*" +
            "([\\u4e00-\\u9fa5A-Za-z0-9.+#/\\- ]{1,20}?)\\s*" +
            "(?:选择题|单选题|多选题|填空题|算法题|场景题|题|的题)"
    );

    private static final Pattern COUNT_PATTERN = Pattern.compile(
            "([一二三四五六七八九十两\\d]+)\\s*[道题个]"
    );

    private static final Pattern INTERVIEW_TOPIC_PATTERN = Pattern.compile(
            "(?:开始|开启|来|进行)\\s*一?场?\\s*([\\u4e00-\\u9fa5A-Za-z0-9.+#/ ]{1,20}?)\\s*(?:的?面试|的?模拟面试)"
    );

    private static final Pattern REPORT_REQUEST_PATTERN = Pattern.compile(
            "^(?:请)?(?:帮我|给我|帮忙|麻烦|现在|直接)?\\s*(?:生成|出|查看|查询|看下?)\\s*(?:一份)?(?:面试)?(?:总结|报告).*$"
    );

    private static final Pattern PROFILE_REQUEST_PATTERN = Pattern.compile(
            "^(?:请)?(?:帮我|给我|帮忙|麻烦|我想|我要|现在|直接)?\\s*(?:查询|查看|看下?|获取)\\s*(?:我的)?(?:学习画像|学习计划|训练计划|画像).*$"
    );

    /**
     * 对用户原始输入进行规则前置过滤。
     *
     * @param query 用户输入的原始文本
     * @return 如果规则命中返回 PreFilterResult，否则返回 empty
     */
    @TraceNode(type = "INTENT", name = "PRE_FILTER")
    public Optional<PreFilterResult> filter(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        String trimmed = query.trim();

        if (trimmed.equalsIgnoreCase("/help")) {
            return Optional.of(PreFilterResult.directReply(HELP_REPLY));
        }
        if (trimmed.equalsIgnoreCase("/clear")) {
            return Optional.of(PreFilterResult.routedWithReply("CLEAR_CONTEXT", Map.of(), CLEAR_REPLY));
        }

        String stripped = trimmed.replaceAll("[!！？?。.~，,\\s]+$", "");
        for (String greeting : GREETINGS) {
            if (stripped.equalsIgnoreCase(greeting)) {
                return Optional.of(PreFilterResult.directReply(GREETING_REPLY));
            }
        }
        if (stripped.length() <= 8) {
            for (String greeting : GREETINGS) {
                if (stripped.startsWith(greeting) && stripped.substring(greeting.length()).matches("[啊呀哇呢吖嘞哦哈]*")) {
                    return Optional.of(PreFilterResult.directReply(GREETING_REPLY));
                }
            }
        }

        for (String keyword : REPORT_KEYWORDS) {
            if (trimmed.contains(keyword) && isReportRequest(trimmed)) {
                return Optional.of(PreFilterResult.routed("INTERVIEW_REPORT", Map.of()));
            }
        }

        for (String prefix : INTERVIEW_START_PREFIXES) {
            if (trimmed.startsWith(prefix)) {
                if (isShortInterviewPrefix(prefix) && !trimmed.contains("面试")) {
                    continue;
                }
                Map<String, Object> slots = new LinkedHashMap<>();
                Matcher matcher = INTERVIEW_TOPIC_PATTERN.matcher(trimmed);
                if (matcher.find()) {
                    String topic = cleanTopic(matcher.group(1));
                    if (!topic.isBlank()) {
                        slots.put("topic", topic);
                    }
                }
                if (trimmed.contains("跳过自我介绍") || trimmed.contains("直接出题") || trimmed.contains("跳过介绍")) {
                    slots.put("skipIntro", true);
                }
                return Optional.of(PreFilterResult.routed("INTERVIEW_START", slots));
            }
        }

        if ((trimmed.contains("刷题") || trimmed.contains("编码练习")) && !isNegativeCodingIntent(trimmed)) {
            return Optional.of(PreFilterResult.routed("CODING_PRACTICE", extractCodingSlots(trimmed)));
        }
        for (String prefix : CODING_PRACTICE_PREFIXES) {
            if (trimmed.startsWith(prefix)) {
                return Optional.of(PreFilterResult.routed("CODING_PRACTICE", extractCodingSlots(trimmed)));
            }
        }

        for (String keyword : PROFILE_KEYWORDS) {
            if (trimmed.contains(keyword) && isProfileRequest(trimmed)) {
                return Optional.of(PreFilterResult.routed("PROFILE_TRAINING_PLAN_QUERY", Map.of()));
            }
        }

        // ===== 域级检测（兜底：无法命中具体意图时，尝试识别业务域） =====

        // INTERVIEW 域（优先于 CODING，因为"面试题"应归 INTERVIEW）
        for (String keyword : INTERVIEW_DOMAIN_KEYWORDS) {
            if (trimmed.contains(keyword)) {
                return Optional.of(PreFilterResult.domainOnly("INTERVIEW"));
            }
        }

        // CODING 域
        for (String keyword : CODING_DOMAIN_KEYWORDS) {
            if (trimmed.contains(keyword) && !isNegativeCodingIntent(trimmed)) {
                return Optional.of(PreFilterResult.domainOnly("CODING", extractCodingSlots(trimmed)));
            }
        }

        // KNOWLEDGE 域（正则匹配技术问答句式）
        if (KNOWLEDGE_QUESTION_PATTERN.matcher(trimmed).find()) {
            return Optional.of(PreFilterResult.domainOnly("KNOWLEDGE"));
        }

        // PROFILE 域
        for (String keyword : PROFILE_DOMAIN_KEYWORDS) {
            if (trimmed.contains(keyword)) {
                return Optional.of(PreFilterResult.domainOnly("PROFILE"));
            }
        }

        return Optional.empty();
    }

    /**
     * 从刷题类查询中提取槽位（topic、questionType、count）。
     */
    private Map<String, Object> extractCodingSlots(String query) {
        Map<String, Object> slots = new LinkedHashMap<>();

        if (query.contains("选择") || query.contains("单选") || query.contains("多选")) {
            slots.put("questionType", "CHOICE");
        } else if (query.contains("填空") || query.contains("补全")) {
            slots.put("questionType", "FILL");
        } else if (query.contains("场景")) {
            slots.put("questionType", "SCENARIO");
        } else if (query.contains("算法")) {
            slots.put("questionType", "ALGORITHM");
        }

        Matcher topicMatcher = TOPIC_PATTERN.matcher(query);
        if (topicMatcher.find()) {
            String topic = cleanTopic(topicMatcher.group(1));
            if (!topic.isEmpty()
                    && !"选择".equals(topic)
                    && !"填空".equals(topic)
                    && !"算法".equals(topic)
                    && !"场景".equals(topic)) {
                slots.put("topic", topic);
            }
        }

        Matcher countMatcher = COUNT_PATTERN.matcher(query);
        if (countMatcher.find()) {
            int count = parseChineseNumber(countMatcher.group(1));
            if (count > 0) {
                slots.put("count", count);
            }
        }

        return slots;
    }

    /**
     * 从原始 query 中补全可能缺失的槽位。
     * 供 TaskRouterAgent 在 Layer 1 或 Layer 2 命中后调用，避免重复的兜底逻辑。
     *
     * @param query   用户原始输入
     * @param payload 当前已有的槽位 map（会被就地修改）
     */
    public void fillMissingSlots(String query, Map<String, Object> payload) {
        if (query == null || query.isBlank() || payload == null) {
            return;
        }

        if (!payload.containsKey("count") || payload.get("count") == null) {
            Matcher matcher = COUNT_PATTERN.matcher(query);
            if (matcher.find()) {
                int count = parseChineseNumber(matcher.group(1));
                if (count > 0) {
                    payload.put("count", count);
                }
            }
        }

        if (query.contains("跳过自我介绍") || query.contains("直接出题") || query.contains("跳过介绍")) {
            payload.put("skipIntro", true);
        }
    }

    private int parseChineseNumber(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            // Fall through to common Chinese numerals.
        }
        return switch (value) {
            case "一" -> 1;
            case "两", "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> 0;
        };
    }

    private boolean isShortInterviewPrefix(String prefix) {
        return SHORT_INTERVIEW_PREFIXES.contains(prefix);
    }

    private boolean isNegativeCodingIntent(String query) {
        return query.matches(".*(?:不想|不要|别|不用|不再|停止).*(?:刷题|编码练习).*");
    }

    private boolean isReportRequest(String query) {
        return REPORT_REQUEST_PATTERN.matcher(query).matches();
    }

    private boolean isProfileRequest(String query) {
        return PROFILE_REQUEST_PATTERN.matcher(query).matches();
    }

    private String cleanTopic(String rawTopic) {
        if (rawTopic == null) {
            return "";
        }
        return rawTopic.trim().replaceAll("\\s+", " ").replaceFirst("\\s*的$", "").trim();
    }
}
