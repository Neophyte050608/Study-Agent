package com.example.interview.service;

import com.example.interview.core.Question;
import com.example.interview.tool.WebSearchTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG 核心服务。
 * 负责检索查询改写、混合召回、证据整理、回答评估与可观测追踪。
 */
@Service
public class RAGService {

    private static final Logger logger = LoggerFactory.getLogger(RAGService.class);
    private static final Pattern EVIDENCE_LINE_PATTERN = Pattern.compile("^(\\d+)\\.\\s+(.*)$");
    private static final Pattern INDEX_PATTERN = Pattern.compile("(\\d+)");
    private static final Pattern RAW_API_KEY_PATTERN = Pattern.compile("(?i)(\"?api[-_ ]?key\"?\\s*[:=]\\s*\"?)([^\",\\s]+)");
    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)([A-Za-z0-9._-]{8,})");
    private static final Pattern LONG_TOKEN_PATTERN = Pattern.compile("\\b[A-Za-z0-9._-]{32,}\\b");

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final LexicalIndexService lexicalIndexService;
    private final WebSearchTool webSearchTool;
    private final RAGObservabilityService observabilityService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RAGService(@org.springframework.beans.factory.annotation.Qualifier("openAiChatModel") org.springframework.ai.chat.model.ChatModel chatModel, VectorStore vectorStore, LexicalIndexService lexicalIndexService, WebSearchTool webSearchTool, RAGObservabilityService observabilityService) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("你是一位专业的中文技术面试官与复盘助手。")
                .build();
        this.vectorStore = vectorStore;
        this.lexicalIndexService = lexicalIndexService;
        this.webSearchTool = webSearchTool;
        this.observabilityService = observabilityService;
    }

    /**
     * 评估入口：执行“检索→评估→证据校验→观测记录”的完整链路。
     */
    public String processAnswer(String topic, String question, String userAnswer, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot) {
        long startAt = System.currentTimeMillis();
        KnowledgePacket packet = buildKnowledgePacket(question, userAnswer);
        try {
            String validated = evaluateWithKnowledge(topic, question, userAnswer, difficultyLevel, followUpState, topicMastery, profileSnapshot, "", packet);
            recordTrace(packet.retrievalQuery(), packet.retrievedDocs().size(), packet.retrievalEvidence(), validated, false, System.currentTimeMillis() - startAt);
            return validated;
        } catch (RuntimeException e) {
            logger.warn("回答评估失败，返回降级结果。原因: {}", summarizeError(e));
            String fallback = buildFallbackEvaluation(question, e);
            recordTrace(packet.retrievalQuery(), packet.retrievedDocs().size(), packet.retrievalEvidence(), fallback, true, System.currentTimeMillis() - startAt);
            return fallback;
        }
    }

    public KnowledgePacket buildKnowledgePacket(String question, String userAnswer) {
        // 先做关键词改写，再走向量+词法混合检索，必要时回退网络搜索。
        String retrievalQuery = question + " " + userAnswer;
        try {
            retrievalQuery = callWithRetry(() -> rewriteQuery(question, userAnswer), 2, "关键词提取");
        } catch (RuntimeException e) {
            logger.warn("关键词提取失败，使用原问答检索。原因: {}", summarizeError(e));
        }
        logger.info("Rewritten Query: {}", retrievalQuery);
        List<Document> retrievedDocs = retrieveHybridDocuments(retrievalQuery);
        String context = retrievedDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));
        String retrievalEvidence = buildRetrievalEvidence(retrievedDocs);
        boolean webFallbackUsed = false;
        if (context.isBlank()) {
            List<String> webContext = webSearchTool.run(new WebSearchTool.Query(retrievalQuery, 3));
            context = webContext.stream().collect(Collectors.joining("\n\n"));
            retrievalEvidence = buildWebEvidence(webContext);
            webFallbackUsed = true;
        }
        return new KnowledgePacket(retrievalQuery, retrievedDocs, context, retrievalEvidence, webFallbackUsed);
    }

    public String evaluateWithKnowledge(String topic, String question, String userAnswer, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot, String strategyHint, KnowledgePacket packet) {
        // 统一注入策略提示与证据目录，约束模型输出可引用的证据范围。
        String finalContext = packet == null ? "" : packet.context();
        String finalEvidence = packet == null ? "[]" : packet.retrievalEvidence();
        String normalizedStrategy = strategyHint == null ? "" : strategyHint.trim();
        String raw = callWithRetry(() -> generateEvaluation(topic, question, userAnswer, difficultyLevel, followUpState, topicMastery, profileSnapshot, finalContext, finalEvidence, normalizedStrategy), 2, "回答评估");
        return validateEvidenceReferences(raw, finalEvidence);
    }

    private String validateEvidenceReferences(String rawJson, String retrievalEvidence) {
        Map<Integer, String> allowedEvidence = parseEvidenceCatalog(retrievalEvidence);
        if (allowedEvidence.isEmpty()) {
            return rawJson;
        }
        if (rawJson == null || rawJson.isBlank() || !rawJson.trim().startsWith("{")) {
            return rawJson;
        }
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            if (!(node instanceof ObjectNode objectNode)) {
                return rawJson;
            }
            List<String> citations = readArrayText(objectNode.path("citations"));
            List<String> conflicts = readArrayText(objectNode.path("conflicts"));
            List<String> deductions = readArrayText(objectNode.path("deductions"));
            List<String> validCitations = citations.stream()
                    .filter(item -> containsAllowedEvidence(item, allowedEvidence.keySet()))
                    .collect(Collectors.toList());
            List<String> validConflicts = conflicts.stream()
                    .filter(item -> containsAllowedEvidence(item, allowedEvidence.keySet()))
                    .collect(Collectors.toList());
            boolean filtered = validCitations.size() != citations.size() || validConflicts.size() != conflicts.size();
            if (validCitations.isEmpty()) {
                Map.Entry<Integer, String> first = allowedEvidence.entrySet().iterator().next();
                validCitations = List.of(first.getKey() + ". " + first.getValue());
            }
            if (filtered) {
                deductions.add("存在未命中证据索引的引用或冲突项，已自动过滤。");
            }
            objectNode.set("citations", toArrayNode(validCitations));
            objectNode.set("conflicts", toArrayNode(validConflicts));
            objectNode.set("deductions", toArrayNode(deductions));
            return objectMapper.writeValueAsString(objectNode);
        } catch (Exception e) {
            logger.warn("引用校验失败，返回原始评估结果。原因: {}", summarizeError(e));
            return rawJson;
        }
    }

    private List<Document> retrieveHybridDocuments(String retrievalQuery) {
        List<Document> vectorDocs = List.of();
        try {
            vectorDocs = vectorStore.similaritySearch(
                    SearchRequest.builder().query(retrievalQuery).topK(8).build()
            );
        } catch (RuntimeException e) {
            logger.warn("向量检索失败，继续使用关键词检索。原因: {}", summarizeError(e));
        }
        List<Document> lexicalDocs = lexicalIndexService.search(retrievalQuery, 8);
        List<Document> fused = reciprocalRankFuse(vectorDocs, lexicalDocs);
        return rerankByQueryOverlap(retrievalQuery, fused, 5);
    }

    private List<Document> reciprocalRankFuse(List<Document> vectorDocs, List<Document> lexicalDocs) {
        Map<String, FusedCandidate> fused = new LinkedHashMap<>();
        for (int i = 0; i < vectorDocs.size(); i++) {
            Document doc = vectorDocs.get(i);
            String key = candidateKey(doc);
            FusedCandidate candidate = fused.computeIfAbsent(key, k -> new FusedCandidate(doc));
            double sourceBoost = sourceTypeBoost(doc);
            candidate.score += (1.0 / (60 + i + 1)) * sourceBoost;
        }
        for (int i = 0; i < lexicalDocs.size(); i++) {
            Document doc = lexicalDocs.get(i);
            String key = candidateKey(doc);
            FusedCandidate candidate = fused.computeIfAbsent(key, k -> new FusedCandidate(doc));
            double sourceBoost = sourceTypeBoost(doc);
            candidate.score += (1.0 / (60 + i + 1)) * sourceBoost;
            Object lexicalScore = doc.getMetadata().get("lexical_score");
            if (lexicalScore instanceof Number number) {
                candidate.score += number.doubleValue() * 0.01 * sourceBoost;
            }
        }
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(FusedCandidate::score).reversed())
                .map(FusedCandidate::document)
                .collect(Collectors.toList());
    }

    private List<Document> rerankByQueryOverlap(String query, List<Document> docs, int topK) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return docs.stream().limit(topK).collect(Collectors.toList());
        }
        Map<Document, Double> scoreMap = new HashMap<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            List<String> docTokens = tokenize(doc.getText());
            long overlap = queryTokens.stream().filter(docTokens::contains).count();
            double overlapRatio = docTokens.isEmpty() ? 0.0 : (double) overlap / (double) Math.min(queryTokens.size(), Math.max(1, docTokens.size()));
            double score = (overlapRatio * 0.7 + (1.0 / (i + 1)) * 0.3) * sourceTypeBoost(doc);
            scoreMap.put(doc, score);
        }
        return docs.stream()
                .sorted((a, b) -> Double.compare(scoreMap.getOrDefault(b, 0.0), scoreMap.getOrDefault(a, 0.0)))
                .limit(topK)
                .collect(Collectors.toList());
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(text.toLowerCase(Locale.ROOT)
                        .replaceAll("[^\\p{L}\\p{N}\\s_#-]", " ")
                        .replaceAll("\\s+", " ")
                        .trim()
                        .split(" "))
                .stream()
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .collect(Collectors.toList());
    }

    private Map<Integer, String> parseEvidenceCatalog(String retrievalEvidence) {
        Map<Integer, String> catalog = new LinkedHashMap<>();
        if (retrievalEvidence == null || retrievalEvidence.isBlank() || "[]".equals(retrievalEvidence.trim())) {
            return catalog;
        }
        String[] lines = retrievalEvidence.split("\\R");
        for (String line : lines) {
            Matcher matcher = EVIDENCE_LINE_PATTERN.matcher(line.trim());
            if (matcher.find()) {
                int index = Integer.parseInt(matcher.group(1));
                String text = matcher.group(2).trim();
                if (!text.isBlank()) {
                    catalog.put(index, text.length() > 120 ? text.substring(0, 120) : text);
                }
            }
        }
        return catalog;
    }

    private List<String> readArrayText(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        node.forEach(item -> {
            String text = item == null ? "" : item.asText("");
            if (!text.isBlank()) {
                result.add(text.trim());
            }
        });
        return result;
    }

    private boolean containsAllowedEvidence(String text, Set<Integer> allowedIndexes) {
        if (text == null || text.isBlank() || allowedIndexes == null || allowedIndexes.isEmpty()) {
            return false;
        }
        Matcher matcher = INDEX_PATTERN.matcher(text);
        Set<Integer> indexes = new TreeSet<>();
        while (matcher.find()) {
            try {
                indexes.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        if (indexes.isEmpty()) {
            return false;
        }
        return indexes.stream().anyMatch(allowedIndexes::contains);
    }

    private ArrayNode toArrayNode(List<String> values) {
        ArrayNode node = objectMapper.createArrayNode();
        if (values == null || values.isEmpty()) {
            return node;
        }
        values.stream()
                .filter(item -> item != null && !item.isBlank())
                .forEach(item -> node.add(item.trim()));
        return node;
    }

    private void recordTrace(String query, int retrievedCount, String evidence, String resultJson, boolean fallbackUsed, long latencyMs) {
        try {
            JsonNode node = objectMapper.readTree(resultJson);
            int score = node.path("score").asInt(0);
            int citationsCount = node.path("citations").isArray() ? node.path("citations").size() : 0;
            int conflictsCount = node.path("conflicts").isArray() ? node.path("conflicts").size() : 0;
            int evidenceCount = parseEvidenceCatalog(evidence).size();
            observabilityService.record(new RAGObservabilityService.TraceRecord(
                    Instant.now(),
                    query,
                    retrievedCount,
                    evidenceCount,
                    citationsCount,
                    conflictsCount,
                    score,
                    fallbackUsed,
                    latencyMs
            ));
        } catch (Exception ignored) {
        }
    }

    private String candidateKey(Document doc) {
        if (doc == null) {
            return "null";
        }
        String path = String.valueOf(doc.getMetadata().getOrDefault("file_path", "unknown"));
        String text = doc.getText() == null ? "" : doc.getText().trim();
        String head = text.length() > 80 ? text.substring(0, 80) : text;
        return path + "::" + head;
    }

    private String rewriteQuery(String question, String userAnswer) {
        return chatClient.prompt()
                .user(u -> u.text("请从下面的面试问答中提取用于知识检索的关键词。\n" +
                        "问题：{question}\n" +
                        "回答：{answer}\n" +
                        "只返回关键词或检索短语，不要返回其他解释。")
                        .param("question", question)
                        .param("answer", userAnswer))
                .call()
                .content();
    }

    private String generateEvaluation(String topic, String question, String userAnswer, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot, String context, String retrievalEvidence, String strategyHint) {
        String difficultyGuide = normalizeDifficultyGuide(difficultyLevel);
        String promptTemplate = "你是一位技术面试官。\n\n" +
                "面试主题：{topic}\n" +
                "当前难度：{difficultyGuide}\n" +
                "追问状态：{followUpState}\n" +
                "当前主题能力值：{topicMastery}\n" +
                "本轮出题与追问策略：{strategyHint}\n" +
                "历史画像：\n{profileSnapshot}\n\n" +
                "知识库上下文：\n" +
                "{context}\n\n" +
                "证据索引：\n" +
                "{retrievalEvidence}\n\n" +
                "当前问题：{question}\n" +
                "用户回答：{userAnswer}\n\n" +
                "任务：\n" +
                "1. 从知识点准确性、表达逻辑、深度、边界考虑四个维度分别评分（0-100）。\n" +
                "2. 给出总分，并明确扣分原因。\n" +
                "3. 如果知识库中出现该题相关笔记但回答不全/不对，要在反馈中显式标注【笔记已覆盖但回答缺失】。\n" +
                "4. 追问必须基于用户当前回答继续深挖，不要跳题库。\n" +
                "5. 难度自适应：低分时回到基础概念；稳定高分时转场景题、原理题、手写思路题。\n\n" +
                "6. citations 仅能填写证据索引中出现的编号和摘要，不允许捏造。\n" +
                "7. conflicts 仅在回答与证据或常识冲突时填写，格式为“冲突点｜参考证据编号”。\n\n" +
                "输出必须是严格 JSON，不要包含 markdown 代码块，不要输出任何额外文字。\n" +
                "JSON 字段：score,accuracy,logic,depth,boundary,deductions(array),citations(array),conflicts(array),feedback,nextQuestion。";

        return chatClient.prompt()
                .user(u -> u.text(promptTemplate)
                        .param("topic", topic)
                        .param("difficultyGuide", difficultyGuide)
                        .param("followUpState", followUpState)
                        .param("topicMastery", String.format("%.1f", topicMastery))
                        .param("strategyHint", strategyHint)
                        .param("profileSnapshot", profileSnapshot)
                        .param("context", context)
                        .param("retrievalEvidence", retrievalEvidence)
                        .param("question", question)
                        .param("userAnswer", userAnswer))
                .call()
                .content();
    }

    private String callWithRetry(Supplier<String> action, int maxAttempts, String stage) {
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

    private String buildFallbackEvaluation(String question, Throwable throwable) {
        String feedback = isTimeout(throwable)
                ? "本次回答分析超时，请重试一次或缩短回答后再提交。"
                : "本次回答分析服务暂时不可用，请稍后重试。";
        return "{\"score\":0,\"accuracy\":0,\"logic\":0,\"depth\":0,\"boundary\":0," +
                "\"deductions\":[\"服务降级，未产出扣分点\"]," +
                "\"citations\":[]," +
                "\"conflicts\":[]," +
                "\"feedback\":\"" + jsonEscape(feedback) + "\"," +
                "\"nextQuestion\":\"" + jsonEscape(question) + "\"}";
    }

    private String buildRetrievalEvidence(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "[]";
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            Map<String, Object> metadata = doc.getMetadata();
            String path = metadata == null ? "" : String.valueOf(metadata.getOrDefault("file_path", "unknown"));
            String sourceType = metadata == null ? "" : String.valueOf(metadata.getOrDefault("source_type", "obsidian"));
            String tags = metadata == null ? "" : String.valueOf(metadata.getOrDefault("knowledge_tags", ""));
            String text = doc.getText() == null ? "" : doc.getText().replaceAll("\\s+", " ").trim();
            if (text.length() > 90) {
                text = text.substring(0, 90);
            }
            lines.add((i + 1) + ". [" + sourceType + ":" + path + "] tags=" + tags + " | " + text);
        }
        return String.join("\n", lines);
    }

    private String buildWebEvidence(List<String> webContext) {
        if (webContext == null || webContext.isEmpty()) {
            return "[]";
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < webContext.size(); i++) {
            String text = webContext.get(i);
            if (text == null) {
                continue;
            }
            String normalized = text.replaceAll("\\s+", " ").trim();
            if (normalized.length() > 90) {
                normalized = normalized.substring(0, 90);
            }
            lines.add((i + 1) + ". [web] " + normalized);
        }
        return lines.isEmpty() ? "[]" : String.join("\n", lines);
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static class FusedCandidate {
        private final Document document;
        private double score;

        private FusedCandidate(Document document) {
            this.document = document;
            this.score = 0.0;
        }

        public Document document() {
            return document;
        }

        public double score() {
            return score;
        }
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String compactMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }

    String summarizeError(Throwable throwable) {
        String compact = sanitizeUpstreamText(compactMessage(throwable));
        String upstream = extractUpstreamDetail(throwable);
        if (upstream.isBlank()) {
            return compact;
        }
        return compact + " | upstream=" + upstream;
    }

    private String extractUpstreamDetail(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RestClientResponseException responseException) {
                String body = responseException.getResponseBodyAsString();
                String detail = "status=" + responseException.getStatusCode().value() + ", body=" + sanitizeUpstreamText(body);
                return truncate(detail, 360);
            }
            current = current.getCause();
        }
        if (throwable == null || throwable.getMessage() == null) {
            return "";
        }
        return truncate(sanitizeUpstreamText(throwable.getMessage()), 300);
    }

    private String sanitizeUpstreamText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String sanitized = text.replaceAll("\\s+", " ").trim();
        sanitized = RAW_API_KEY_PATTERN.matcher(sanitized).replaceAll("$1***");
        sanitized = AUTHORIZATION_PATTERN.matcher(sanitized).replaceAll("$1***");
        sanitized = LONG_TOKEN_PATTERN.matcher(sanitized).replaceAll("***");
        return sanitized;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "...";
    }
    
    public String generateFirstQuestion(String resumeContent, String topic, String profileSnapshot) {
         return chatClient.prompt()
                .user(u -> u.text("你是一位技术面试官。\n" +
                        "简历摘要：{resume}\n" +
                        "面试主题：{topic}\n" +
                        "用户历史画像：{profileSnapshot}\n" +
                        "请优先围绕用户笔记或项目经历已出现的内容出题，同时兼顾薄弱点。\n" +
                        "请生成第一道中文面试题，问题要简洁、专业。")
                        .param("resume", resumeContent)
                        .param("topic", topic)
                        .param("profileSnapshot", profileSnapshot))
                .call()
                .content();
    }

    public String generateFinalReport(String topic, List<Question> history, String targetedSuggestion) {
        String qaHistory = history.stream()
                .map(item -> "问题：" + item.getQuestionText() + "\n" +
                        "回答：" + item.getUserAnswer() + "\n" +
                        "得分：" + item.getScore() + "\n" +
                        "点评：" + item.getFeedback())
                .collect(Collectors.joining("\n\n---\n\n"));

        String prompt = "你是技术面试复盘官。\n" +
                "面试主题：{topic}\n" +
                "后续训练建议参考：\n{targetedSuggestion}\n" +
                "以下是完整答题记录：\n{history}\n\n" +
                "请输出最终复盘报告，必须严格使用以下标签：\n" +
                "<summary>整体总结（3-5句）</summary>\n" +
                "<incomplete>回答不全的点（按要点分行）</incomplete>\n" +
                "<weak>回答不好的点（按要点分行）</weak>\n" +
                "<wrong>回答错误的点（按要点分行）</wrong>\n" +
                "<obsidian_updates>可直接补充进 Obsidian 的内容建议（分点）</obsidian_updates>\n" +
                "<next_focus>下一轮针对性出题方向（分点）</next_focus>\n" +
                "要求：中文、具体、可执行，不要输出其他标签。";
        try {
            return callWithRetry(() -> chatClient.prompt()
                    .user(u -> u.text(prompt)
                            .param("topic", topic)
                            .param("targetedSuggestion", targetedSuggestion)
                            .param("history", qaHistory))
                    .call()
                    .content(), 2, "最终复盘");
        } catch (RuntimeException e) {
            logger.warn("最终复盘生成失败，返回降级报告。原因: {}", summarizeError(e));
            return buildFallbackReport(history, targetedSuggestion);
        }
    }

    private String buildFallbackReport(List<Question> history, String targetedSuggestion) {
        String incomplete = history.stream()
                .filter(q -> q.getScore() >= 60 && q.getScore() < 80)
                .map(q -> "问题《" + q.getQuestionText() + "》：要点覆盖不完整。")
                .collect(Collectors.joining("\n"));
        String weak = history.stream()
                .filter(q -> q.getScore() >= 40 && q.getScore() < 60)
                .map(q -> "问题《" + q.getQuestionText() + "》：表达或结构较弱。")
                .collect(Collectors.joining("\n"));
        String wrong = history.stream()
                .filter(q -> q.getScore() < 40)
                .map(q -> "问题《" + q.getQuestionText() + "》：存在明显事实或原理错误。")
                .collect(Collectors.joining("\n"));
        String summary = "已完成本轮面试，请优先复习低分题的核心原理，并通过结构化回答提升完整度。";
        return "<summary>" + summary + "</summary>\n" +
                "<incomplete>" + (incomplete.isBlank() ? "暂无明显不完整回答。" : incomplete) + "</incomplete>\n" +
                "<weak>" + (weak.isBlank() ? "暂无明显薄弱表达。" : weak) + "</weak>\n" +
                "<wrong>" + (wrong.isBlank() ? "暂无明确错误结论。" : wrong) + "</wrong>\n" +
                "<obsidian_updates>建议补充：每道低分题补充定义、原理、场景和边界条件。</obsidian_updates>\n" +
                "<next_focus>" + (targetedSuggestion == null || targetedSuggestion.isBlank() ? "下一轮聚焦低分题相关主题。" : targetedSuggestion) + "</next_focus>";
    }

    private String normalizeDifficultyGuide(String difficultyLevel) {
        if (difficultyLevel == null) {
            return "基础巩固";
        }
        String normalized = difficultyLevel.toUpperCase(Locale.ROOT);
        if ("ADVANCED".equals(normalized)) {
            return "高级深挖（场景、原理、手写思路）";
        }
        if ("INTERMEDIATE".equals(normalized)) {
            return "中级进阶（原理+实战）";
        }
        return "基础巩固";
    }

    private double sourceTypeBoost(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return 1.0;
        }
        String sourceType = String.valueOf(doc.getMetadata().getOrDefault("source_type", "obsidian")).toLowerCase(Locale.ROOT);
        if ("interview_experience".equals(sourceType)) {
            return 1.18;
        }
        if ("web".equals(sourceType)) {
            return 0.92;
        }
        return 1.0;
    }

    public record KnowledgePacket(
            String retrievalQuery,
            List<Document> retrievedDocs,
            String context,
            String retrievalEvidence,
            boolean webFallbackUsed
    ) {
    }
}
