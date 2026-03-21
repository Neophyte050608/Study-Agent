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
 *
 * <p>关键约定：</p>
 * <ul>
 *   <li>证据索引：buildRetrievalEvidence/buildWebEvidence 会生成带编号的证据目录，评估输出的 citations/conflicts 只能引用这些编号。</li>
 *   <li>安全日志：日志输出会尽量使用长度与脱敏后的内容，避免把 API Key、Bearer token 等敏感信息写入日志。</li>
 *   <li>降级路径：模型/检索不可用时返回可读的 fallback JSON，保证前端流程可继续。</li>
 * </ul>
 */
@Service
public class RAGService {

    private static final Logger logger = LoggerFactory.getLogger(RAGService.class);
    private static final Pattern EVIDENCE_LINE_PATTERN = Pattern.compile("^(\\d+)\\.\\s+(.*)$");
    private static final Pattern INDEX_PATTERN = Pattern.compile("(\\d+)");
    // 用于日志脱敏：尽量在调试时保留“字段存在”信息，但不暴露实际密钥/令牌。
    private static final Pattern RAW_API_KEY_PATTERN = Pattern.compile("(?i)(\"?api[-_ ]?key\"?\\s*[:=]\\s*\"?)([^\",\\s]+)");
    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)([A-Za-z0-9._-]{8,})");
    private static final Pattern LONG_TOKEN_PATTERN = Pattern.compile("\\b[A-Za-z0-9._-]{32,}\\b");

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final LexicalIndexService lexicalIndexService;
    private final WebSearchTool webSearchTool;
    private final RAGObservabilityService observabilityService;
    private final AgentSkillService agentSkillService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RAGService(@org.springframework.beans.factory.annotation.Qualifier("openAiChatModel") org.springframework.ai.chat.model.ChatModel chatModel, VectorStore vectorStore, LexicalIndexService lexicalIndexService, WebSearchTool webSearchTool, RAGObservabilityService observabilityService, AgentSkillService agentSkillService) {
        this.agentSkillService = agentSkillService;
        String globalSkillInstruction = safeSkillText(this.agentSkillService.globalInstruction());
        String systemPrompt = "你是一位专业的中文技术面试官与复盘助手。";
        if (!globalSkillInstruction.isBlank()) {
            systemPrompt = systemPrompt + "\n\n" + globalSkillInstruction;
        }
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
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
        // 注意：retrievalQuery 可能包含用户原话，日志里只用于排障；必要时可将该日志级别改为 DEBUG。
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
        String originalContext = packet == null ? "" : packet.context();
        String originalEvidence = packet == null ? "[]" : packet.retrievalEvidence();
        String finalContext = truncate(originalContext, 1400);
        String finalEvidence = truncate(originalEvidence, 900);
        String safeProfileSnapshot = truncate(profileSnapshot, 480);
        String normalizedStrategy = strategyHint == null ? "" : strategyHint.trim();
        logger.info(
                "评估调用参数: topic={}, difficulty={}, followUp={}, mastery={}, questionLen={}, answerLen={}, strategyLen={}, profileLen={}/{}, contextLen={}/{}, evidenceLen={}/{}, evidenceCount={}, retrievedDocs={}, webFallbackUsed={}",
                safeLogText(topic, 40),
                safeLogText(difficultyLevel, 24),
                safeLogText(followUpState, 24),
                String.format("%.1f", topicMastery),
                safeLength(question),
                safeLength(userAnswer),
                safeLength(normalizedStrategy),
                safeLength(safeProfileSnapshot),
                safeLength(profileSnapshot),
                safeLength(finalContext),
                safeLength(originalContext),
                safeLength(finalEvidence),
                safeLength(originalEvidence),
                parseEvidenceCatalog(originalEvidence).size(),
                packet == null || packet.retrievedDocs() == null ? 0 : packet.retrievedDocs().size(),
                packet != null && packet.webFallbackUsed()
        );
        try {
            String raw = callWithRetry(() -> generateEvaluation(topic, question, userAnswer, difficultyLevel, followUpState, topicMastery, safeProfileSnapshot, finalContext, finalEvidence, normalizedStrategy), 2, "回答评估");
            return validateEvidenceReferences(raw, finalEvidence);
        } catch (RuntimeException e) {
            logger.warn("回答评估失败，返回降级结果。原因: {}", summarizeError(e));
            return buildFallbackEvaluation(question, e);
        }
    }

    private String validateEvidenceReferences(String rawJson, String retrievalEvidence) {
        Map<Integer, String> allowedEvidence = parseEvidenceCatalog(retrievalEvidence);
        if (allowedEvidence.isEmpty()) {
            return rawJson;
        }
        
        String cleanJson = rawJson;
        if (cleanJson != null) {
            cleanJson = cleanJson.trim();
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.substring(7);
            } else if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.substring(3);
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
            }
            cleanJson = cleanJson.trim();
        }

        if (cleanJson == null || cleanJson.isBlank() || !cleanJson.startsWith("{")) {
            return rawJson;
        }
        try {
            JsonNode node = objectMapper.readTree(cleanJson);
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
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("knowledge-retrieval"));
        return chatClient.prompt()
                .user(u -> u.text("{skillBlock}\n" +
                        "请从下面的面试问答中提取用于知识检索的关键词。\n" +
                        "问题：{question}\n" +
                        "回答：{answer}\n" +
                        "只返回关键词或检索短语，不要返回其他解释。")
                        .param("skillBlock", skillBlock)
                        .param("question", question)
                        .param("answer", userAnswer))
                .call()
                .content();
    }

    private String generateEvaluation(String topic, String question, String userAnswer, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot, String context, String retrievalEvidence, String strategyHint) {
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("evidence-evaluator", "question-strategy", "interview-learning-profile"));
        String difficultyGuide = normalizeDifficultyGuide(difficultyLevel);
        String promptTemplate = "{skillBlock}\n" +
                "你是一位技术面试官。\n\n" +
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
                        .param("skillBlock", skillBlock)
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
        // 统一重试包装：用于模型调用/改写等“外部依赖”步骤，降低偶发超时/抖动对整体流程的影响。
        // 退避策略：线性 sleep（400ms * attempt），避免短时间内连续打满下游。
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
        // 证据目录格式："{index}. [source_type:file_path] tags=... | snippet"
        // 该格式会被 parseEvidenceCatalog 与 validateEvidenceReferences 使用，以约束 citations/conflicts 的可引用范围。
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
        // Web 证据没有稳定的 file_path/source_type，统一标记为 [web] 并做摘要截断。
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

    private int safeLength(String text) {
        return text == null ? 0 : text.length();
    }

    private String safeLogText(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return truncate(sanitizeUpstreamText(text), maxLength);
    }
    
    public String generateFirstQuestion(String resumeContent, String topic, String profileSnapshot) {
         String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("question-strategy", "interview-learning-profile"));
         try {
            String rawQuestion = callWithRetry(() -> chatClient.prompt()
                     .user(u -> u.text("{skillBlock}\n" +
                             "你是一位技术面试官。\n" +
                             "简历摘要：{resume}\n" +
                             "面试主题：{topic}\n" +
                             "用户历史画像：{profileSnapshot}\n" +
                             "请根据用户的历史画像，**专门针对其薄弱点（Weaknesses）或得分较低的技能**生成第一道中文面试题。如果画像中没有薄弱点，则优先围绕其项目经历出题。\n" +
                             "只输出一道题目本身，必须单行，不要标题、不要策略说明、不要理由、不要建议、不要 markdown。")
                             .param("skillBlock", skillBlock)
                            .param("resume", truncate(resumeContent, 1500))
                             .param("topic", topic)
                            .param("profileSnapshot", truncate(profileSnapshot, 500)))
                     .call()
                     .content(), 2, "首题生成");
             return normalizeFirstQuestion(rawQuestion, topic);
         } catch (RuntimeException e) {
             logger.warn("首题生成失败，返回降级问题。原因: {}", summarizeError(e));
             return buildFallbackFirstQuestion(topic);
         }
    }

    public String generateFinalReport(String topic, List<Question> history, String targetedSuggestion) {
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("interview-growth-coach"));
        String qaHistory = history.stream()
                .map(item -> "问题：" + item.getQuestionText() + "\n" +
                        "回答：" + item.getUserAnswer() + "\n" +
                        "得分：" + item.getScore() + "\n" +
                        "点评：" + item.getFeedback())
                .collect(Collectors.joining("\n\n---\n\n"));

        String prompt = "{skillBlock}\n" +
                "你是技术面试复盘官。\n" +
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
                            .param("skillBlock", skillBlock)
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

    public String generateCodingQuestion(String topic, String difficulty, String profileSnapshot) {
        String normalizedTopic = topic == null || topic.isBlank() ? "数组与字符串" : topic.trim();
        String normalizedDifficulty = difficulty == null || difficulty.isBlank() ? "medium" : difficulty.trim().toLowerCase(Locale.ROOT);
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("question-strategy", "interview-learning-profile"));
        String prompt = "{skillBlock}\n" +
                "你是一位算法训练教练，请生成一道中文算法题。\n" +
                "主题：{topic}\n" +
                "难度：{difficulty}\n" +
                "用户画像：{profileSnapshot}\n" +
                "输出要求：\n" +
                "1. 只输出题目正文与输入输出约束，禁止输出答案。\n" +
                "2. 单段文本，不要 markdown，不要标题。\n";
        try {
            String raw = callWithRetry(() -> chatClient.prompt()
                    .user(u -> u.text(prompt)
                            .param("skillBlock", skillBlock)
                            .param("topic", normalizedTopic)
                            .param("difficulty", normalizedDifficulty)
                            .param("profileSnapshot", truncate(profileSnapshot, 240)))
                    .call()
                    .content(), 2, "刷题题目生成");
            if (raw == null || raw.isBlank()) {
                return buildFallbackCodingQuestion(normalizedTopic, normalizedDifficulty);
            }
            return truncate(raw.replace("\r\n", " ").replaceAll("\\s+", " ").trim(), 280);
        } catch (RuntimeException e) {
            logger.warn("刷题题目生成失败，返回降级题目。原因: {}", summarizeError(e));
            return buildFallbackCodingQuestion(normalizedTopic, normalizedDifficulty);
        }
    }

    public CodingAssessment evaluateCodingAnswer(String topic, String difficulty, String question, String answer) {
        String normalizedTopic = topic == null || topic.isBlank() ? "算法" : topic.trim();
        String normalizedDifficulty = difficulty == null || difficulty.isBlank() ? "medium" : difficulty.trim().toLowerCase(Locale.ROOT);
        String safeQuestion = question == null ? "" : question.trim();
        String safeAnswer = answer == null ? "" : answer.trim();
        if (safeAnswer.isBlank()) {
            return new CodingAssessment(20, "未提供有效答案。", "先补充完整思路，再给出复杂度分析。", fallbackNextCodingQuestion(normalizedTopic, 20));
        }
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("evidence-evaluator"));
        String prompt = "{skillBlock}\n" +
                "你是一位算法面试评估官，请评估候选人的题解文本。\n" +
                "主题：{topic}\n" +
                "难度：{difficulty}\n" +
                "题目：{question}\n" +
                "回答：{answer}\n" +
                "请输出严格 JSON：score,feedback,nextHint,nextQuestion。\n" +
                "score 范围 0-100。";
        try {
            String raw = callWithRetry(() -> chatClient.prompt()
                    .user(u -> u.text(prompt)
                            .param("skillBlock", skillBlock)
                            .param("topic", normalizedTopic)
                            .param("difficulty", normalizedDifficulty)
                            .param("question", truncate(safeQuestion, 280))
                            .param("answer", truncate(safeAnswer, 800)))
                    .call()
                    .content(), 2, "刷题答案评估");
            String clean = normalizeJsonContent(raw);
            JsonNode node = objectMapper.readTree(clean);
            int score = node.path("score").asInt(0);
            String feedback = node.path("feedback").asText("建议补充完整思路并说明复杂度。");
            String nextHint = node.path("nextHint").asText("请优先补充边界条件与复杂度分析。");
            String nextQuestion = node.path("nextQuestion").asText("");
            if (nextQuestion == null || nextQuestion.isBlank()) {
                nextQuestion = generateNextCodingQuestion(normalizedTopic, normalizedDifficulty, safeQuestion, safeAnswer, score);
            }
            return new CodingAssessment(Math.max(0, Math.min(score, 100)), feedback, nextHint, truncate(nextQuestion, 220));
        } catch (Exception e) {
            logger.warn("刷题答案评估失败，返回降级评分。原因: {}", summarizeError(e));
            return fallbackCodingAssessment(safeAnswer);
        }
    }

    public String generateNextCodingQuestion(String topic, String difficulty, String question, String answer, int score) {
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("question-strategy"));
        String prompt = "{skillBlock}\n" +
                "你是一位算法训练教练，请根据当前答题质量生成下一道追问题。\n" +
                "主题：{topic}\n" +
                "难度：{difficulty}\n" +
                "当前题目：{question}\n" +
                "用户回答：{answer}\n" +
                "当前得分：{score}\n" +
                "要求：\n" +
                "1. 只输出下一题题干；\n" +
                "2. 若当前分数较低，下一题降一级难度并聚焦基础；\n" +
                "3. 若当前分数较高，下一题同主题进阶追问。";
        try {
            String raw = callWithRetry(() -> chatClient.prompt()
                    .user(u -> u.text(prompt)
                            .param("skillBlock", skillBlock)
                            .param("topic", topic == null ? "算法" : topic)
                            .param("difficulty", difficulty == null ? "medium" : difficulty)
                            .param("question", truncate(question, 240))
                            .param("answer", truncate(answer, 500))
                            .param("score", String.valueOf(score)))
                    .call()
                    .content(), 2, "刷题下一题生成");
            if (raw == null || raw.isBlank()) {
                return fallbackNextCodingQuestion(topic, score);
            }
            return truncate(raw.replace("\r\n", " ").replaceAll("\\s+", " ").trim(), 220);
        } catch (RuntimeException e) {
            logger.warn("刷题下一题生成失败，返回降级问题。原因: {}", summarizeError(e));
            return fallbackNextCodingQuestion(topic, score);
        }
    }

    public String generateLearningPlan(String topic, String weakPoint, String recentPerformance) {
        String normalizedTopic = topic == null || topic.isBlank() ? "后端基础" : topic.trim();
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("interview-learning-profile", "interview-growth-coach"));
        String prompt = "{skillBlock}\n" +
                "你是一位学习教练，请基于用户状态生成 7 天学习计划。\n" +
                "主题：{topic}\n" +
                "薄弱点：{weakPoint}\n" +
                "近期表现：{recentPerformance}\n" +
                "输出要求：\n" +
                "1. 使用纯文本，每天一行；\n" +
                "2. 每行包含：目标 + 练习动作 + 复盘动作；\n" +
                "3. 不要 markdown 标题。";
        try {
            String raw = callWithRetry(() -> chatClient.prompt()
                    .user(u -> u.text(prompt)
                            .param("skillBlock", skillBlock)
                            .param("topic", normalizedTopic)
                            .param("weakPoint", truncate(weakPoint, 180))
                            .param("recentPerformance", truncate(recentPerformance, 200)))
                    .call()
                    .content(), 2, "学习计划生成");
            if (raw == null || raw.isBlank()) {
                return fallbackLearningPlan(normalizedTopic, weakPoint);
            }
            return raw.trim();
        } catch (RuntimeException e) {
            logger.warn("学习计划生成失败，返回降级计划。原因: {}", summarizeError(e));
            return fallbackLearningPlan(normalizedTopic, weakPoint);
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

    private String buildFallbackFirstQuestion(String topic) {
        String safeTopic = topic == null || topic.isBlank() ? "你最熟悉的后端主题" : topic.trim();
        return "我们先从基础开始，请你结合一个实际场景，说明你对「" + safeTopic + "」的核心概念、典型实现和常见误区的理解。";
    }

    private String buildFallbackCodingQuestion(String topic, String difficulty) {
        return "请完成一道" + difficulty + "难度的「" + topic + "」算法题：给定整数数组与目标值，返回两数之和等于目标值的下标，并说明时间复杂度与空间复杂度。";
    }

    private String fallbackNextCodingQuestion(String topic, int score) {
        String safeTopic = topic == null || topic.isBlank() ? "数组与字符串" : topic;
        if (score < 60) {
            return "请给出一道「" + safeTopic + "」基础题的完整暴力解，并说明如何优化到更低时间复杂度。";
        }
        if (score < 80) {
            return "请继续完成一道「" + safeTopic + "」中等题，并重点说明边界条件和复杂度推导。";
        }
        return "请完成一道「" + safeTopic + "」进阶题，并比较两种不同解法的 trade-off。";
    }

    private String fallbackLearningPlan(String topic, String weakPoint) {
        String weak = weakPoint == null || weakPoint.isBlank() ? "复杂度分析与边界处理" : weakPoint;
        return "Day1: 梳理 " + topic + " 核心概念，完成 2 道基础题，记录错误清单。\n" +
                "Day2: 复习 " + weak + "，完成 1 道中等题，复盘复杂度。\n" +
                "Day3: 总结高频模板，完成 2 道同主题题，提炼可复用步骤。\n" +
                "Day4: 针对薄弱点做专项训练，完成 1 道限时题，复盘超时原因。\n" +
                "Day5: 进行模拟讲解，口述解题过程，补齐表达短板。\n" +
                "Day6: 完成 1 道进阶题，对比两种解法并总结取舍。\n" +
                "Day7: 做一次 30 分钟小测，整理错题并形成下周目标。";
    }

    private CodingAssessment fallbackCodingAssessment(String answer) {
        String lower = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        int score = 35;
        if (lower.contains("o(")) {
            score += 20;
        }
        if (lower.contains("for") || lower.contains("while")) {
            score += 15;
        }
        if (lower.contains("hashmap") || lower.contains("map")) {
            score += 15;
        }
        if (lower.contains("边界") || lower.contains("null") || lower.contains("空")) {
            score += 10;
        }
        score = Math.min(score, 90);
        String feedback = score < 60 ? "题解描述不完整，建议补充步骤与边界场景。"
                : "题解基本可用，建议进一步优化复杂度论证。";
        String nextHint = score < 60 ? "先给出可运行解法，再补充复杂度。"
                : "尝试提供另一种实现并比较 trade-off。";
        return new CodingAssessment(score, feedback, nextHint, fallbackNextCodingQuestion("算法", score));
    }

    private String normalizeJsonContent(String raw) {
        if (raw == null) {
            return "";
        }
        String clean = raw.trim();
        if (clean.startsWith("```json")) {
            clean = clean.substring(7);
        } else if (clean.startsWith("```")) {
            clean = clean.substring(3);
        }
        if (clean.endsWith("```")) {
            clean = clean.substring(0, clean.length() - 3);
        }
        return clean.trim();
    }

    private String normalizeFirstQuestion(String raw, String topic) {
        if (raw == null || raw.isBlank()) {
            return buildFallbackFirstQuestion(topic);
        }
        String normalized = raw.replace("\r\n", "\n")
                .replace("**", "")
                .replace("`", "")
                .trim();
        String[] lines = normalized.split("\\n");
        for (String line : lines) {
            String candidate = line == null ? "" : line.trim();
            if (candidate.isBlank()) {
                continue;
            }
            candidate = candidate
                    .replaceAll("^[\\-•*\\d.\\s]+", "")
                    .replaceAll("^题目[：:]", "")
                    .replaceAll("^第一题[：:]", "")
                    .trim();
            if (candidate.startsWith("出题依据") || candidate.startsWith("策略提示") || candidate.startsWith("后续建议")) {
                continue;
            }
            if (candidate.contains("？") || candidate.contains("?")) {
                return truncateToQuestion(candidate);
            }
        }
        for (String line : lines) {
            String candidate = line == null ? "" : line.trim();
            if (candidate.isBlank()) {
                continue;
            }
            candidate = candidate
                    .replaceAll("^[\\-•*\\d.\\s]+", "")
                    .replaceAll("^题目[：:]", "")
                    .replaceAll("^第一题[：:]", "")
                    .trim();
            if (candidate.startsWith("出题依据") || candidate.startsWith("策略提示") || candidate.startsWith("后续建议")) {
                continue;
            }
            if (!candidate.isBlank()) {
                return truncate(candidate, 140);
            }
        }
        return buildFallbackFirstQuestion(topic);
    }

    private String truncateToQuestion(String text) {
        int endCn = text.lastIndexOf('？');
        int endEn = text.lastIndexOf('?');
        int end = Math.max(endCn, endEn);
        if (end < 0) {
            return truncate(text, 140);
        }
        return text.substring(0, end + 1).trim();
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

    private String safeSkillText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content.trim();
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

    public record CodingAssessment(
            int score,
            String feedback,
            String nextHint,
            String nextQuestion
    ) {
    }
}
