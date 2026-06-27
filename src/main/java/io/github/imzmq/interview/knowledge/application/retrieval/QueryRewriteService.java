package io.github.imzmq.interview.knowledge.application.retrieval;

import io.github.imzmq.interview.agent.application.AgentSkillService;
import io.github.imzmq.interview.knowledge.application.support.UpstreamErrorSanitizer;
import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.modelrouting.core.TimeoutHint;
import io.github.imzmq.interview.observability.core.RAGTraceContext;
import io.github.imzmq.interview.skill.core.SkillExecutionBudget;
import io.github.imzmq.interview.skill.core.SkillExecutionContext;
import io.github.imzmq.interview.skill.core.SkillExecutionResult;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class QueryRewriteService {

    private static final Logger logger = LoggerFactory.getLogger(QueryRewriteService.class);
    private static final Duration REWRITE_CACHE_TTL = Duration.ofMinutes(10);
    private static final int REWRITE_CACHE_MAX_SIZE = 256;
    private static final Set<String> REWRITE_TRIGGER_KEYWORDS = Set.of(
            "为什么", "怎么", "如何", "原理", "区别", "对比", "场景", "实现",
            "设计", "优化", "排查", "分析", "步骤", "问题", "异常", "报错",
            "以及", "并且", "但是", "不过", "是否", "还是", "包括", "比如"
    );

    private final RoutingChatService routingChatService;
    private final AgentSkillService agentSkillService;
    private final SkillOrchestrator skillOrchestrator;
    private final ConcurrentHashMap<String, CachedRewrite> rewriteCache = new ConcurrentHashMap<>();

    public QueryRewriteService(RoutingChatService routingChatService,
                               AgentSkillService agentSkillService,
                               SkillOrchestrator skillOrchestrator) {
        this.routingChatService = routingChatService;
        this.agentSkillService = agentSkillService;
        this.skillOrchestrator = skillOrchestrator;
    }

    public RewrittenQuery buildRewrittenQuery(String question, String userAnswer, SkillExecutionBudget skillBudget) {
        String fallbackRaw = normalizeRewriteSource(question, userAnswer);
        if (!shouldRewriteQuery(question, userAnswer)) {
            return RewrittenQuery.fallback(fallbackRaw);
        }
        String cacheKey = buildRewriteCacheKey(question, userAnswer);
        CachedRewrite cachedRewrite = rewriteCache.get(cacheKey);
        if (cachedRewrite != null && !cachedRewrite.isExpired()) {
            return cachedRewrite.query();
        }
        try {
            SkillExecutionResult result = skillOrchestrator.execute(
                    "query-optimizer",
                    new SkillExecutionContext(
                            RAGTraceContext.getTraceId(),
                            "rag-service",
                            Map.of(
                                    "question", question == null ? "" : question,
                                    "userAnswer", userAnswer == null ? "" : userAnswer
                            ),
                            skillBudget
                    )
            );
            RewrittenQuery rewrittenQuery = result.succeeded()
                    ? new RewrittenQuery(
                    result.textOutput("coreTerms"),
                    result.textOutput("expandTerms"),
                    result.textOutput("fullQuery").isBlank() ? fallbackRaw : result.textOutput("fullQuery")
            )
                    : callWithRetry(() -> rewriteQuery(question, userAnswer), 2, "关键词提取");
            putRewriteCache(cacheKey, rewrittenQuery);
            return rewrittenQuery;
        } catch (RuntimeException e) {
            logger.warn("关键词提取失败，使用原问答检索。原因: {}", summarizeError(e));
            return RewrittenQuery.fallback(fallbackRaw);
        }
    }

    private RewrittenQuery rewriteQuery(String question, String userAnswer) {
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("query-optimizer"));
        String prompt = skillBlock + "\n" +
                "请从下面的面试问答中提取用于知识检索的关键词。\n" +
                "严格按 CORE/EXPAND 两行格式输出。\n" +
                "问题：" + question + "\n" +
                "回答：" + userAnswer + "\n" +
                "只返回 CORE 和 EXPAND 两行，不要返回其他解释。";
        String raw = routingChatService.callWithFirstPacketProbeSupplier(
                () -> question,
                prompt,
                ModelRouteType.GENERAL,
                TimeoutHint.NORMAL,
                "关键词提取"
        );
        return parseRewrittenQuery(raw);
    }

    private boolean shouldRewriteQuery(String question, String userAnswer) {
        String normalizedQuestion = question == null ? "" : question.trim();
        String normalizedAnswer = userAnswer == null ? "" : userAnswer.trim();
        if (!normalizedAnswer.isBlank()) {
            return true;
        }
        if (normalizedQuestion.length() >= 28) {
            return true;
        }
        if (normalizedQuestion.contains("，")
                || normalizedQuestion.contains(",")
                || normalizedQuestion.contains("；")
                || normalizedQuestion.contains(";")
                || normalizedQuestion.contains("：")
                || normalizedQuestion.contains(":")
                || normalizedQuestion.contains("（")
                || normalizedQuestion.contains("(")) {
            return true;
        }
        for (String keyword : REWRITE_TRIGGER_KEYWORDS) {
            if (normalizedQuestion.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String buildRewriteCacheKey(String question, String userAnswer) {
        return normalizeRewriteSource(question, userAnswer).toLowerCase(Locale.ROOT);
    }

    private String normalizeRewriteSource(String question, String userAnswer) {
        String combined = ((question == null ? "" : question.trim()) + " " + (userAnswer == null ? "" : userAnswer.trim())).trim();
        return combined.replaceAll("\\s+", " ");
    }

    private void putRewriteCache(String cacheKey, RewrittenQuery query) {
        if (rewriteCache.size() >= REWRITE_CACHE_MAX_SIZE) {
            cleanupExpiredRewriteCache();
            if (rewriteCache.size() >= REWRITE_CACHE_MAX_SIZE) {
                String firstKey = rewriteCache.keys().hasMoreElements() ? rewriteCache.keys().nextElement() : null;
                if (firstKey != null) {
                    rewriteCache.remove(firstKey);
                }
            }
        }
        rewriteCache.put(cacheKey, new CachedRewrite(query, System.currentTimeMillis() + REWRITE_CACHE_TTL.toMillis()));
    }

    private void cleanupExpiredRewriteCache() {
        long now = System.currentTimeMillis();
        rewriteCache.entrySet().removeIf(entry -> entry.getValue().expireAtMs() <= now);
    }

    private RewrittenQuery parseRewrittenQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return RewrittenQuery.fallback("");
        }
        String core = "";
        String expand = "";
        for (String line : raw.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("CORE:")) {
                core = trimmed.substring(5).trim();
            } else if (trimmed.toUpperCase(Locale.ROOT).startsWith("EXPAND:")) {
                expand = trimmed.substring(7).trim();
            }
        }
        if (core.isEmpty()) {
            return RewrittenQuery.fallback(raw.replaceAll("\\s+", " ").trim());
        }
        String full = expand.isEmpty() ? core : core + " " + expand;
        return new RewrittenQuery(core, expand, full);
    }

    private <T> T callWithRetry(Supplier<T> action, int maxAttempts, String stage) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                last = e;
                logger.warn("{}第{}次调用失败: {}", stage, attempt, summarizeError(e));
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(400L * attempt);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("线程中断", interruptedException);
                    }
                }
            }
        }
        throw last == null ? new IllegalStateException(stage + "失败") : last;
    }

    String summarizeError(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + UpstreamErrorSanitizer.sanitize(message));
    }

    private String safeSkillText(String content) {
        return content == null ? "" : content;
    }

    private record CachedRewrite(RewrittenQuery query, long expireAtMs) {
        private boolean isExpired() {
            return expireAtMs <= System.currentTimeMillis();
        }
    }
}
