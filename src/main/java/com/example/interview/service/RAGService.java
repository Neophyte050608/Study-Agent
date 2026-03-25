package com.example.interview.service;

import com.example.interview.config.ObservabilitySwitchProperties;
import com.example.interview.config.ParentChildRetrievalProperties;
import com.example.interview.core.Question;
import com.example.interview.core.RAGTraceContext;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.RoutingChatService;
import com.example.interview.tool.WebSearchTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;
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

    private final RoutingChatService routingChatService;
    private final VectorStore vectorStore;
    private final LexicalIndexService lexicalIndexService;
    private final WebSearchTool webSearchTool;
    private final RAGObservabilityService observabilityService;
    private final AgentSkillService agentSkillService;
    private final PromptTemplateService promptTemplateService;
    private final PromptManager promptManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    // 注入自定义检索线程池
    private final java.util.concurrent.Executor ragRetrieveExecutor;
    // 注入图谱持久化组件
    private final com.example.interview.graph.TechConceptRepository techConceptRepository;
    // 可观测开关配置
    private final ObservabilitySwitchProperties observabilitySwitchProperties;
    private final ParentChildRetrievalProperties parentChildRetrievalProperties;
    private final ParentChildIndexService parentChildIndexService;

    public RAGService(RoutingChatService routingChatService, VectorStore vectorStore, LexicalIndexService lexicalIndexService, WebSearchTool webSearchTool, RAGObservabilityService observabilityService, AgentSkillService agentSkillService, PromptTemplateService promptTemplateService, PromptManager promptManager, @org.springframework.beans.factory.annotation.Qualifier("ragRetrieveExecutor") java.util.concurrent.Executor ragRetrieveExecutor, com.example.interview.graph.TechConceptRepository techConceptRepository, ObservabilitySwitchProperties observabilitySwitchProperties, ParentChildRetrievalProperties parentChildRetrievalProperties, ParentChildIndexService parentChildIndexService) {
        this.agentSkillService = agentSkillService;
        this.promptTemplateService = promptTemplateService;
        this.promptManager = promptManager;
        this.ragRetrieveExecutor = ragRetrieveExecutor;
        this.techConceptRepository = techConceptRepository;
        this.routingChatService = routingChatService;
        this.vectorStore = vectorStore;
        this.lexicalIndexService = lexicalIndexService;
        this.webSearchTool = webSearchTool;
        this.observabilityService = observabilityService;
        this.observabilitySwitchProperties = observabilitySwitchProperties;
        this.parentChildRetrievalProperties = parentChildRetrievalProperties;
        this.parentChildIndexService = parentChildIndexService;
    }

    public record EvaluationResult(String json, int inputTokens, int outputTokens) {}

    /**
     * 评估入口：执行“检索→评估→证据校验→观测记录”的完整链路。
     */
    public String processAnswer(String topic, String question, String userAnswer, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot) {
        KnowledgePacket packet = buildKnowledgePacket(question, userAnswer);
        try {
            EvaluationResult result = evaluateWithKnowledge(topic, question, userAnswer, difficultyLevel, followUpState, topicMastery, profileSnapshot, "", packet);
            return validateEvidenceReferences(result.json(), packet.retrievalEvidence());
        } catch (RuntimeException e) {
            logger.warn("回答评估失败，返回降级结果。原因: {}", summarizeError(e));
            return buildFallbackEvaluation(question, e);
        }
    }

    public KnowledgePacket buildKnowledgePacket(String question, String userAnswer) {
        // 先做关键词改写，再走向量+词法混合检索，必要时回退网络搜索。
        String traceId = RAGTraceContext.getTraceId();
        String rewriteNodeId = UUID.randomUUID().toString();
        observabilityService.startNode(traceId, rewriteNodeId, RAGTraceContext.getCurrentNodeId(), "REWRITE", "Query Rewrite");
        
        String retrievalQuery = question + " " + userAnswer;
        try {
            retrievalQuery = callWithRetry(() -> rewriteQuery(question, userAnswer), 2, "关键词提取");
            observabilityService.endNode(traceId, rewriteNodeId, "Q: " + question, "RW: " + retrievalQuery, null);
        } catch (RuntimeException e) {
            logger.warn("关键词提取失败，使用原问答检索。原因: {}", summarizeError(e));
            observabilityService.endNode(traceId, rewriteNodeId, "Q: " + question, "FALLBACK: " + retrievalQuery, e.getMessage());
        }
        // 注意：retrievalQuery 可能包含用户原话，日志里只用于排障；必要时可将该日志级别改为 DEBUG。
        if (observabilitySwitchProperties.isRagTraceEnabled()) {
            logger.info("Rewritten Query: {}", retrievalQuery);
        }
        
        String retrievalNodeId = UUID.randomUUID().toString();
        observabilityService.startNode(traceId, retrievalNodeId, RAGTraceContext.getCurrentNodeId(), "RETRIEVAL", "Hybrid Retrieval");
        
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
        
        observabilityService.endNode(traceId, retrievalNodeId, retrievalQuery, retrievedDocs.size() + " docs retrieved, web fallback: " + webFallbackUsed, null);
        
        return new KnowledgePacket(retrievalQuery, retrievedDocs, context, retrievalEvidence, webFallbackUsed);
    }

    public EvaluationResult evaluateWithKnowledge(String topic, String question, String userAnswer, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot, String strategyHint, KnowledgePacket packet) {
        String originalContext = packet == null ? "" : packet.context();
        String originalEvidence = packet == null ? "[]" : packet.retrievalEvidence();
        String finalContext = truncate(originalContext, 1400);
        String finalEvidence = truncate(originalEvidence, 900);
        String safeProfileSnapshot = truncate(profileSnapshot, 480);
        String normalizedStrategy = strategyHint == null ? "" : strategyHint.trim();
        if (observabilitySwitchProperties.isRagTraceEnabled()) {
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
        }
        try {
            RoutingChatService.RoutingResult routingResult = callWithRetryResult(() -> generateEvaluationResult(topic, question, userAnswer, difficultyLevel, followUpState, topicMastery, safeProfileSnapshot, finalContext, finalEvidence, normalizedStrategy), 2, "回答评估");
            return new EvaluationResult(routingResult.content(), routingResult.inputTokens(), routingResult.outputTokens());
        } catch (RuntimeException e) {
            logger.warn("回答评估失败，返回降级结果。原因: {}", summarizeError(e));
            return new EvaluationResult(buildFallbackEvaluation(question, e), 0, 0);
        }
    }

    private RoutingChatService.RoutingResult generateEvaluationResult(String topic, String question, String userAnswer, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot, String context, String retrievalEvidence, String strategyHint) {
        String traceId = RAGTraceContext.getTraceId();
        String nodeId = UUID.randomUUID().toString();
        observabilityService.startNode(traceId, nodeId, RAGTraceContext.getCurrentNodeId(), "GENERATION", "LLM Evaluation");
        
        try {
            String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("evidence-evaluator", "question-strategy"));
            String difficultyGuide = normalizeDifficultyGuide(difficultyLevel);
            List<Map<String, Object>> cases = promptTemplateService.loadFewShotCases("prompts/evaluation_cases.json");
            
            Map<String, Object> contextMap = new HashMap<>();
            contextMap.put("skillBlock", skillBlock);
            contextMap.put("topic", topic);
            contextMap.put("difficultyGuide", difficultyGuide);
            contextMap.put("followUpState", followUpState);
            contextMap.put("topicMastery", String.format("%.1f", topicMastery));
            contextMap.put("strategyHint", strategyHint);
            contextMap.put("profileSnapshot", profileSnapshot);
            contextMap.put("context", context);
            contextMap.put("retrievalEvidence", retrievalEvidence);
            contextMap.put("cases", cases);
            contextMap.put("question", question);
            contextMap.put("userAnswer", userAnswer);

            String prompt = promptManager.render("evaluation", contextMap);
            // 使用带首包探测的同步调用，避免长时间等待无效响应
            RoutingChatService.RoutingResult result = callWithRetryResult(() -> {
                String content = routingChatService.callWithFirstPacketProbeSupplier(
                    () -> { throw new RuntimeException("首包探测超时或失败"); },
                    prompt, ModelRouteType.THINKING, "回答评估"
                );
                // 暂时由于 callWithFirstPacketProbeSupplier 只返回 content，这里做一个包装，真正的 metadata 需后续补充或在此忽略
                return new RoutingChatService.RoutingResult(content, 0, 0, 0);
            }, 1, "回答评估");
            
            observabilityService.endNode(traceId, nodeId, "Q: " + question, "Score: " + result.content().substring(0, Math.min(20, result.content().length())), null);
            return result;
        } catch (Exception e) {
            observabilityService.endNode(traceId, nodeId, "Q: " + question, null, e.getMessage());
            throw e;
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
            // 兼容可能存在的 markdown 标记，无论是否标明了 json 语言
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

            // 修复大模型偶尔遗漏 nextQuestion 字段的问题，提供兜底保护
            if (!objectNode.has("nextQuestion") || objectNode.get("nextQuestion").asText().isBlank()) {
                objectNode.put("nextQuestion", "能否进一步结合实际项目场景，谈谈你在使用这项技术时遇到的最大挑战及解决方案？");
            }

            return objectMapper.writeValueAsString(objectNode);
        } catch (Exception e) {
            logger.warn("引用校验失败，返回原始评估结果。原因: {}", summarizeError(e));
            return rawJson;
        }
    }

    /**
     * 核心多路召回引擎。
     * 
     * 【技术演进与设计思考】
     * 1. 痛点：原来的单一向量检索（Vector Search）会因为“语义漂移”导致实体边界模糊。比如搜“Redis 缓存击穿”，它会召回大量“MySQL 缓存”的八股文。
     * 2. 优化：引入基于倒排/词法匹配的“意图定向通道”，结合 `knowledge_tags` 权重，确保精准命中专业术语；再保留全局向量作为兜底，提高泛化能力。
     * 3. 并发：通过自定义线程池 `ragRetrieveExecutor` 让三路通道并发执行，整体耗时取决于最慢的一路，不增加接口 RT。
     * 
     * 实现：意图定向检索 + 全局向量检索 + 图谱关联检索 并行执行。
     */
    private List<Document> retrieveHybridDocuments(String retrievalQuery) {
        List<String> intentFocusTerms = buildIntentFocusTerms(retrievalQuery);
        // 通道 A：意图定向检索（利用词法索引服务进行高精度标签/路径匹配）
        java.util.concurrent.CompletableFuture<List<Document>> intentDirectedFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                List<Document> docs = lexicalIndexService.searchIntentDirected(retrievalQuery, intentFocusTerms, 8);
                return markRetrieveChannel(docs, "intent_directed");
            } catch (RuntimeException e) {
                logger.warn("意图定向检索失败，返回空列表。原因: {}", summarizeError(e));
                return List.<Document>of();
            }
        }, ragRetrieveExecutor);

        // 通道 B：全局向量检索（纯语义兜底）
        java.util.concurrent.CompletableFuture<List<Document>> vectorFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                List<Document> docs = vectorStore.similaritySearch(
                        SearchRequest.builder().query(retrievalQuery).topK(8).build()
                );
                return markRetrieveChannel(docs, "global_vector");
            } catch (RuntimeException e) {
                logger.warn("向量检索失败，返回空列表。原因: {}", summarizeError(e));
                return List.<Document>of();
            }
        }, ragRetrieveExecutor);

        // 通道 C：GraphRAG 检索分支（图谱实体扩展）
        java.util.concurrent.CompletableFuture<List<Document>> graphFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                List<Document> graphDocs = new ArrayList<>();
                List<String> queryTokens = intentFocusTerms.isEmpty() ? tokenize(retrievalQuery) : intentFocusTerms;
                for (String token : queryTokens) {
                    List<String> relatedConcepts = techConceptRepository.findRelatedConceptsWithinTwoHops(token);
                    if (relatedConcepts != null && !relatedConcepts.isEmpty()) {
                        String graphContext = "知识图谱关联提示：与【" + token + "】存在深度技术关联的概念包括 -> " + String.join(", ", relatedConcepts);
                        Document doc = new Document(graphContext);
                        doc.getMetadata().put("source_type", "graph_rag");
                        doc.getMetadata().put("retrieve_channel", "graph_rag");
                        graphDocs.add(doc);
                    }
                }
                return graphDocs;
            } catch (Exception e) {
                logger.warn("图谱检索失败，返回空列表。原因: {}", summarizeError(e));
                return List.<Document>of();
            }
        }, ragRetrieveExecutor);

        List<Document> intentDocs = intentDirectedFuture.join();
        List<Document> vectorDocs = vectorFuture.join();
        List<Document> graphDocs = graphFuture.join();

        // RRF 融合去重与重排流水线
        List<Document> fused = reciprocalRankFuse(intentDocs, vectorDocs);
        fused.addAll(graphDocs);
        List<Document> hydrated = maybeHydrateParentDocuments(fused);
        return rerankByQueryOverlap(retrievalQuery, hydrated, 5);
    }

    private List<Document> maybeHydrateParentDocuments(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        if (!parentChildRetrievalProperties.isEnabled()) {
            return docs;
        }
        int hydrateLimit = Math.max(1, parentChildRetrievalProperties.getHydrateParentTopN());
        List<Document> candidates = docs.stream().limit(hydrateLimit).collect(Collectors.toList());
        Set<String> parentIds = candidates.stream()
                .map(doc -> String.valueOf(doc.getMetadata().getOrDefault("parent_id", "")))
                .filter(item -> item != null && !item.isBlank() && !"null".equalsIgnoreCase(item))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (parentIds.isEmpty()) {
            return docs;
        }
        Map<String, com.example.interview.entity.RagParentDO> parentMap = parentChildIndexService.queryParentsByIds(parentIds);
        if (parentMap.isEmpty()) {
            return docs;
        }
        List<Document> result = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            if (i >= hydrateLimit) {
                result.add(doc);
                continue;
            }
            String parentId = String.valueOf(doc.getMetadata().getOrDefault("parent_id", ""));
            com.example.interview.entity.RagParentDO parent = parentMap.get(parentId);
            if (parent == null || parent.getParentText() == null || parent.getParentText().isBlank()) {
                result.add(doc);
                continue;
            }
            Document hydrated = new Document(parent.getParentText());
            hydrated.getMetadata().putAll(doc.getMetadata());
            hydrated.getMetadata().put("parent_id", parent.getParentId());
            hydrated.getMetadata().put("parent_source", parent.getFilePath());
            hydrated.getMetadata().put("file_path", parent.getFilePath());
            hydrated.getMetadata().put("section_path", parent.getSectionPath());
            hydrated.getMetadata().put("knowledge_tags", parent.getKnowledgeTags());
            hydrated.getMetadata().put("source_type", parent.getSourceType());
            result.add(hydrated);
        }
        return result;
    }

    /**
     * RRF (Reciprocal Rank Fusion) 倒数排名融合算法。
     * 
     * 【为什么要用 RRF？】
     * 1. 分数量纲不一致：向量检索返回的是余弦相似度（通常在 0.7~1.0），而词法检索（TF-IDF）返回的是绝对分数（可能 > 10）。
     * 2. 无法直接相加或简单归一化：强行归一化会被极端离群值带偏。
     * 3. 解决思路：抛弃绝对分数，只看“排名”。公式：Score = 1 / (k + rank)。
     *    文档在两个通道中排名越靠前，倒数和就越大，有效抹平了不同检索引擎的尺度差异。
     * 
     * 同时，在融合时针对 `intent_directed` 意图定向通道的文档给予 `1.12` 的提权（Channel Boost）。
     */
    private List<Document> reciprocalRankFuse(List<Document> intentDirectedDocs, List<Document> globalVectorDocs) {
        Map<String, FusedCandidate> fused = new LinkedHashMap<>();
        for (int i = 0; i < intentDirectedDocs.size(); i++) {
            Document doc = intentDirectedDocs.get(i);
            String key = candidateKey(doc);
            FusedCandidate candidate = fused.computeIfAbsent(key, k -> new FusedCandidate(doc));
            double sourceBoost = sourceTypeBoost(doc);
            candidate.score += (1.0 / (60 + i + 1)) * sourceBoost * channelBoost(doc);
        }
        for (int i = 0; i < globalVectorDocs.size(); i++) {
            Document doc = globalVectorDocs.get(i);
            String key = candidateKey(doc);
            FusedCandidate candidate = fused.computeIfAbsent(key, k -> new FusedCandidate(doc));
            double sourceBoost = sourceTypeBoost(doc);
            candidate.score += (1.0 / (60 + i + 1)) * sourceBoost * channelBoost(doc);
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

    private List<String> buildIntentFocusTerms(String retrievalQuery) {
        if (retrievalQuery == null || retrievalQuery.isBlank()) {
            return List.of();
        }
        return tokenize(retrievalQuery).stream()
                .filter(token -> token.length() >= 2)
                .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), ArrayList::new))
                .stream()
                .limit(8)
                .collect(Collectors.toList());
    }

    private List<Document> markRetrieveChannel(List<Document> docs, String channel) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        for (Document doc : docs) {
            if (doc != null) {
                doc.getMetadata().put("retrieve_channel", channel);
            }
        }
        return docs;
    }

    private double channelBoost(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return 1.0;
        }
        String channel = String.valueOf(doc.getMetadata().getOrDefault("retrieve_channel", "")).toLowerCase(Locale.ROOT);
        if ("intent_directed".equals(channel)) {
            return 1.12;
        }
        return 1.0;
    }

    private List<Document> rerankByQueryOverlap(String query, List<Document> docs, int topK) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        // 为了和词法检索统一分词标准，这里借用了更优的分词逻辑（见下方 tokenize 的优化）
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
        // 为了和 LexicalIndexService 保持一致，同样引入分词逻辑（或者直接复用），这里简单优化为更好的正则分词
        // 因为这段代码仅用于 Query Overlap 的简单计算，不需要像全文索引那样精准，但之前的逻辑也偏弱
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

    private String candidateKey(Document doc) {
        if (doc == null) {
            return "null";
        }
        String path = String.valueOf(doc.getMetadata().getOrDefault("file_path", "unknown"));
        String text = doc.getText() == null ? "" : doc.getText().trim();
        String head = text.length() > 80 ? text.substring(0, 80) : text;
        return path + "::" + head;
    }

    private RoutingChatService.RoutingResult callWithRetryResult(Supplier<RoutingChatService.RoutingResult> action, int maxAttempts, String stage) {
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

    private String rewriteQuery(String question, String userAnswer) {
        // 使用新抽离的 query-optimizer 技能来优化检索关键词
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("query-optimizer"));
        String prompt = skillBlock + "\n" +
                "请从下面的面试问答中提取用于知识检索的关键词。\n" +
                "如果回答过短，请运用 HyDE（假设性文档嵌入）策略，推测并补充几项理想答案中该有的技术专有名词。\n" +
                "问题：" + question + "\n" +
                "回答：" + userAnswer + "\n" +
                "只返回关键词或检索短语，不要返回其他解释。";
        return routingChatService.callWithFirstPacketProbeSupplier(
            () -> question,
            prompt, ModelRouteType.GENERAL, "关键词提取"
        );
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
            String parentId = metadata == null ? "" : String.valueOf(metadata.getOrDefault("parent_id", ""));
            String childIndex = metadata == null ? "" : String.valueOf(metadata.getOrDefault("child_index", ""));
            String tags = metadata == null ? "" : String.valueOf(metadata.getOrDefault("knowledge_tags", ""));
            String text = doc.getText() == null ? "" : doc.getText().replaceAll("\\s+", " ").trim();
            if (text.length() > 90) {
                text = text.substring(0, 90);
            }
            String parentPart = (parentId == null || parentId.isBlank() || "null".equalsIgnoreCase(parentId)) ? "" : " parent=" + parentId;
            String childPart = (childIndex == null || childIndex.isBlank() || "null".equalsIgnoreCase(childIndex)) ? "" : " child=" + childIndex;
            lines.add((i + 1) + ". [" + sourceType + ":" + path + "] tags=" + tags + parentPart + childPart + " | " + text);
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
    
    public String generateFirstQuestion(String resumeContent, String topic, String profileSnapshot, boolean skipIntro) {
         String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("question-strategy", "interview-learning-profile"));
         try {
            System.out.println("====== [RAGService - generateFirstQuestion] Params: topic=" + topic + " skipIntro=" + skipIntro + " ======");
            
            String promptText = skipIntro 
                ? "{skillBlock}\n" +
                  "你是一位技术面试官。\n" +
                  "简历摘要：{resume}\n" +
                  "用户画像：{profileSnapshot}\n" +
                  "面试主题：{topic}\n" +
                  "当前环节：【项目经历与难点挖掘】\n" +
                  "用户要求跳过自我介绍，直接开始提问。请结合用户的简历内容和用户画像，直接抛出第一道关于 {topic} 或简历项目的深度技术问题。\n" +
                  "只输出问题本身，必须单行，不要标题、不要策略说明、不要 markdown。"
                : "{skillBlock}\n" +
                  "你是一位技术面试官。\n" +
                  "简历摘要：{resume}\n" +
                  "面试主题：{topic}\n" +
                  "当前环节：【自我介绍】\n" +
                  "请结合用户的简历内容，生成一段破冰的开场白和第一道面试题。第一题必须是让候选人做自我介绍。\n" +
                  "例如：“你好！看了你的简历，发现你在微服务领域有不少经验。今天我们将进行 {topic} 相关的面试。在正式开始前，能先简单做个自我介绍吗？”\n" +
                  "只输出开场白本身，必须单行，不要标题、不要策略说明、不要 markdown。";

            String renderedPrompt = promptText
                    .replace("{skillBlock}", skillBlock)
                    .replace("{resume}", truncate(resumeContent, 1500))
                    .replace("{profileSnapshot}", truncate(profileSnapshot, 500))
                    .replace("{topic}", topic == null ? "" : topic);
            String rawQuestion = routingChatService.callWithFirstPacketProbeSupplier(
                    () -> buildFallbackFirstQuestion(topic),
                    renderedPrompt,
                    ModelRouteType.GENERAL,
                    "首题生成"
            );
            System.out.println("====== [RAGService - generateFirstQuestion] Response ======");
            System.out.println(rawQuestion);
             return normalizeFirstQuestion(rawQuestion, topic);
         } catch (RuntimeException e) {
             logger.warn("首题生成失败，返回降级问题。原因: {}", summarizeError(e));
             return buildFallbackFirstQuestion(topic);
         }
    }

    public String generateFinalReport(String topic, List<Question> history, String targetedSuggestion, String rollingSummary) {
        // 使用新抽离的 interview-report-generator 和 interview-growth-coach 技能
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("interview-report-generator", "interview-growth-coach"));
        
        // 结合 Rolling Summary 机制，如果存在滚动总结，则将其作为极高权重的历史基线传入
        String baseContext = rollingSummary == null || rollingSummary.isBlank() ? "" : "【前期面试表现滚动总结】\n" + rollingSummary + "\n\n";

        // Contextual Compression: 针对长达几十轮的面试历史，采用摘要式压缩
        // 因为已经有了 rollingSummary 覆盖了前面的对话，这里我们只需要保留最近 5 轮的详细记录即可，防止 Prompt 过长
        int historySize = history.size();
        int recentCount = Math.min(historySize, 5);
        List<Question> recentHistoryList = history.subList(historySize - recentCount, historySize);

        String qaHistory = recentHistoryList.stream()
                .map(item -> {
                    // 对于最近 5 轮，我们仍然保留 150 个字符的截断作为双保险，避免个别回答异常冗长
                    String shortAnswer = item.getUserAnswer() != null && item.getUserAnswer().length() > 150 
                            ? item.getUserAnswer().substring(0, 150) + "..." 
                            : item.getUserAnswer();
                    return "Q: " + item.getQuestionText() + "\n" +
                           "A(简): " + shortAnswer + "\n" +
                           "Score: " + item.getScore() + " | FB: " + item.getFeedback();
                })
                .collect(Collectors.joining("\n\n---\n\n"));

        Map<String, Object> params = new HashMap<>();
        params.put("skillBlock", skillBlock);
        params.put("topic", topic);
        params.put("targetedSuggestion", targetedSuggestion);
        params.put("baseContext", baseContext);
        params.put("history", qaHistory);
        String prompt = promptManager.render("final-report", params);

        try {
            System.out.println("====== [RAGService - generateFinalReport] Prompt Template ======");
            System.out.println(prompt);
            String response = callWithRetry(() -> routingChatService.callWithFirstPacketProbeSupplier(
                () -> buildFallbackReport(history, targetedSuggestion),
                prompt, ModelRouteType.THINKING, "最终复盘"
            ), 1, "最终复盘");
            System.out.println("====== [RAGService - generateFinalReport] Response ======");
            System.out.println(response);
            return response;
        } catch (RuntimeException e) {
            logger.warn("最终复盘生成失败，返回降级报告。原因: {}", summarizeError(e));
            return buildFallbackReport(history, targetedSuggestion);
        }
    }

    public String generateCodingQuestion(String topic, String difficulty, String profileSnapshot) {
        String normalizedTopic = topic == null || topic.isBlank() ? "数组与字符串" : topic.trim();
        String normalizedDifficulty = difficulty == null || difficulty.isBlank() ? "medium" : difficulty.trim().toLowerCase(Locale.ROOT);
        // 使用新抽离的 coding-interview-coach 技能来专注生成题目
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("coding-interview-coach"));
        
        Map<String, Object> params = new HashMap<>();
        params.put("skillBlock", skillBlock);
        params.put("topic", normalizedTopic);
        params.put("difficulty", normalizedDifficulty);
        params.put("profileSnapshot", truncate(profileSnapshot, 240));
        String prompt = promptManager.render("coding-question", params);

        try {
            System.out.println("====== [RAGService - generateCodingQuestion] Prompt Template ======");
            System.out.println(prompt);
            String raw = callWithRetry(() -> routingChatService.callWithFirstPacketProbeSupplier(
                () -> "",
                prompt, ModelRouteType.GENERAL, "刷题题目生成"
            ), 1, "刷题题目生成");
            System.out.println("====== [RAGService - generateCodingQuestion] Response ======");
            System.out.println(raw);
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
        // 使用新抽离的 coding-interview-coach 技能来进行多维度代码审查
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("coding-interview-coach"));
        
        Map<String, Object> params = new HashMap<>();
        params.put("skillBlock", skillBlock);
        params.put("topic", normalizedTopic);
        params.put("difficulty", normalizedDifficulty);
        params.put("question", truncate(safeQuestion, 280));
        params.put("answer", truncate(safeAnswer, 800));
        String prompt = promptManager.render("coding-evaluation", params);

        try {
            System.out.println("====== [RAGService - evaluateCodingAnswer] Prompt Template ======");
            System.out.println(prompt);
            String raw = callWithRetry(() -> routingChatService.callWithFirstPacketProbeSupplier(
                () -> "{\"score\":0,\"feedback\":\"评估超时\"}",
                prompt, ModelRouteType.THINKING, "刷题答案评估"
            ), 1, "刷题答案评估");
            System.out.println("====== [RAGService - evaluateCodingAnswer] Response ======");
            System.out.println(raw);
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
        // 使用新抽离的 coding-interview-coach 技能来生成进阶追问
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("coding-interview-coach"));
        
        Map<String, Object> params = new HashMap<>();
        params.put("skillBlock", skillBlock);
        params.put("topic", topic == null ? "算法" : topic);
        params.put("difficulty", difficulty == null ? "medium" : difficulty);
        params.put("question", truncate(question, 240));
        params.put("answer", truncate(answer, 500));
        params.put("score", String.valueOf(score));
        String prompt = promptManager.render("coding-next-question", params);

        try {
            String raw = callWithRetry(() -> routingChatService.callWithFirstPacketProbeSupplier(
                () -> "",
                prompt, ModelRouteType.GENERAL, "刷题下一题生成"
            ), 1, "刷题下一题生成");
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
        // 使用新抽离的 personalized-learning-planner 技能
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("personalized-learning-planner", "interview-learning-profile", "interview-growth-coach"));
        
        Map<String, Object> params = new HashMap<>();
        params.put("skillBlock", skillBlock);
        params.put("topic", normalizedTopic);
        params.put("weakPoint", truncate(weakPoint, 200));
        params.put("recentPerformance", truncate(recentPerformance, 300));
        String prompt = promptManager.render("learning-plan", params);

        try {
            String raw = callWithRetry(() -> routingChatService.callWithFirstPacketProbeSupplier(
                () -> "",
                prompt, ModelRouteType.GENERAL, "学习计划生成"
            ), 1, "学习计划生成");
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
        String safeTopic = topic == null || topic.isBlank() ? "后端开发" : topic.trim();
        return "你好！欢迎参加今天的「" + safeTopic + "」模拟面试。在正式开始技术交流之前，能请你先花 1-2 分钟做一个简单的自我介绍吗？";
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
