package com.example.interview.service;

import com.example.interview.agent.CodingPracticeAgent;
import com.example.interview.config.ObservabilitySwitchProperties;
import com.example.interview.config.ParentChildRetrievalProperties;
import com.example.interview.config.RagRetrievalProperties;
import com.example.interview.core.Question;
import com.example.interview.core.RAGTraceContext;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.RoutingChatService;
import com.example.interview.modelrouting.TimeoutHint;
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

    /**
     * 结构化查询改写结果。
     * <p>coreTerms 用于高精度检索通道（词法、图谱），
     * fullQuery（core + expand 拼接）用于语义向量检索。</p>
     */
    record RewrittenQuery(String coreTerms, String expandTerms, String fullQuery) {
        static RewrittenQuery fallback(String raw) {
            return new RewrittenQuery(raw, "", raw);
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(RAGService.class);
    private static final Pattern EVIDENCE_LINE_PATTERN = Pattern.compile("^(\\d+)\\.\\s+(.*)$");
    private static final Pattern INDEX_PATTERN = Pattern.compile("(\\d+)");
    // 用于日志脱敏：尽量在调试时保留“字段存在”信息，但不暴露实际密钥/令牌。
    private static final Pattern RAW_API_KEY_PATTERN = Pattern.compile("(?i)(\"?api[-_ ]?key\"?\\s*[:=]\\s*\"?)([^\",\\s]+)");
    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)([A-Za-z0-9._-]{8,})");
    private static final Pattern LONG_TOKEN_PATTERN = Pattern.compile("\\b[A-Za-z0-9._-]{32,}\\b");
    private static final double FINAL_IMAGE_MIN_SCORE = 0.55d;
    private static final double SECOND_IMAGE_MIN_SCORE = 0.68d;
    private static final double ADDITIONAL_IMAGE_SCORE_GAP = 0.18d;
    private static final Set<String> VISUAL_INTENT_KEYWORDS = Set.of(
            "图", "截图", "架构", "流程图", "拓扑", "示意图", "类图", "时序图",
            "部署图", "结构图", "原理图", "对比图", "配置图", "界面", "效果图",
            "看看", "展示", "图解", "diagram", "topology", "layout", "screenshot"
    );

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
    private final RetrievalTokenizerService retrievalTokenizerService;
    private final RagRetrievalProperties ragRetrievalProperties;
    private final ParentChildRetrievalProperties parentChildRetrievalProperties;
    private final ParentChildIndexService parentChildIndexService;
    private final ImageService imageService;

    public RAGService(RoutingChatService routingChatService, VectorStore vectorStore, LexicalIndexService lexicalIndexService, WebSearchTool webSearchTool, RAGObservabilityService observabilityService, AgentSkillService agentSkillService, PromptTemplateService promptTemplateService, PromptManager promptManager, @org.springframework.beans.factory.annotation.Qualifier("ragRetrieveExecutor") java.util.concurrent.Executor ragRetrieveExecutor, com.example.interview.graph.TechConceptRepository techConceptRepository, ObservabilitySwitchProperties observabilitySwitchProperties, RetrievalTokenizerService retrievalTokenizerService, RagRetrievalProperties ragRetrievalProperties, ParentChildRetrievalProperties parentChildRetrievalProperties, ParentChildIndexService parentChildIndexService, @org.springframework.lang.Nullable ImageService imageService) {
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
        this.retrievalTokenizerService = retrievalTokenizerService;
        this.ragRetrievalProperties = ragRetrievalProperties;
        this.parentChildRetrievalProperties = parentChildRetrievalProperties;
        this.parentChildIndexService = parentChildIndexService;
        this.imageService = imageService;
    }

    public record EvaluationResult(String json, int inputTokens, int outputTokens) {}

    /**
     * 评估入口：执行“检索→评估→证据校验→观测记录”的完整链路。
     */
    public String processAnswer(String topic, String question, String userAnswer, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot) {
        return executeWithinTraceRoot("PROCESS", "RAG Answer Evaluation", "Q: " + question, () -> {
            KnowledgePacket packet = buildKnowledgePacket(question, userAnswer);
            try {
                EvaluationResult result = evaluateWithKnowledge(topic, question, userAnswer, difficultyLevel, followUpState, topicMastery, profileSnapshot, "", packet);
                return validateEvidenceReferences(result.json(), packet.retrievalEvidence());
            } catch (RuntimeException e) {
                logger.warn("回答评估失败，返回降级结果。原因: {}", summarizeError(e));
                return buildFallbackEvaluation(question, e);
            }
        });
    }

    /**
     * 构建知识包（默认允许 Web fallback）。
     *
     * <p>该方法通常用于线上链路：如果本地混合检索为空/质量不足，会根据开关策略触发 WebSearch 兜底。</p>
     *
     * @param question  用户问题
     * @param userAnswer 用户回答（可为空；用于改写检索 query）
     * @return 知识包（包含改写 query、召回文档列表、拼接后的上下文与证据目录）
     */
    public KnowledgePacket buildKnowledgePacket(String question, String userAnswer) {
        return buildKnowledgePacket(question, userAnswer, true);
    }

    /**
     * 构建检索知识包，并允许调用方决定是否启用 Web fallback。
     */
    public KnowledgePacket buildKnowledgePacket(String question, String userAnswer, boolean allowWebFallback) {
        return executeWithinTraceRoot("KNOWLEDGE_PACKET", "Knowledge Packet Build", "Q: " + question, () -> {
            // 先做关键词改写，再走向量+词法混合检索，必要时回退网络搜索。
            String traceId = RAGTraceContext.getTraceId();
            String rewriteNodeId = UUID.randomUUID().toString();
            observabilityService.startNode(traceId, rewriteNodeId, RAGTraceContext.getCurrentNodeId(), "REWRITE", "Query Rewrite");

            RewrittenQuery rewrittenQuery = RewrittenQuery.fallback(question + " " + userAnswer);
            try {
                rewrittenQuery = callWithRetry(() -> rewriteQuery(question, userAnswer), 2, "关键词提取");
                observabilityService.endNode(traceId, rewriteNodeId, "Q: " + question,
                        "CORE: " + rewrittenQuery.coreTerms() + " | EXPAND: " + rewrittenQuery.expandTerms(), null);
            } catch (RuntimeException e) {
                logger.warn("关键词提取失败，使用原问答检索。原因: {}", summarizeError(e));
                observabilityService.endNode(traceId, rewriteNodeId, "Q: " + question,
                        "FALLBACK: " + rewrittenQuery.fullQuery(), e.getMessage());
            }
            if (observabilitySwitchProperties.isRagTraceEnabled()) {
                logger.info("Rewritten Query: CORE=[{}] EXPAND=[{}]", rewrittenQuery.coreTerms(), rewrittenQuery.expandTerms());
            }

            String retrievalNodeId = UUID.randomUUID().toString();
            observabilityService.startNode(traceId, retrievalNodeId, RAGTraceContext.getCurrentNodeId(), "RETRIEVAL", "Hybrid Retrieval");

            List<Document> retrievedDocs = retrieveHybridDocuments(rewrittenQuery, 5);
            String context = retrievedDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n"));
            List<ImageService.ImageResult> associatedImages = imageService == null ? List.of() : imageService.findImagesForDocuments(retrievedDocs);
            List<ImageService.ImageResult> semanticImages = imageService == null ? List.of() : imageService.searchRelevantImages(rewrittenQuery.fullQuery(), containsVisualIntent(rewrittenQuery.fullQuery()));
            List<ImageService.ImageResult> retrievedImages = mergeImageResults(associatedImages, semanticImages);
            String imageContext = buildImageContext(retrievedImages);
            String retrievalEvidence = buildRetrievalEvidence(retrievedDocs);
            double bestRetrievalScore = bestRetrievalScore(rewrittenQuery.fullQuery(), retrievedDocs);
            boolean webFallbackUsed = false;
            if (shouldUseWebFallback(allowWebFallback, rewrittenQuery.fullQuery(), retrievedDocs, context, bestRetrievalScore)) {
                List<String> webContext = webSearchTool.run(new WebSearchTool.Query(rewrittenQuery.fullQuery(), 3));
                context = webContext.stream().collect(Collectors.joining("\n\n"));
                retrievalEvidence = buildWebEvidence(webContext);
                webFallbackUsed = true;
            }

            observabilityService.endNode(
                    traceId,
                    retrievalNodeId,
                    rewrittenQuery.fullQuery(),
                    retrievedDocs.size() + " docs retrieved, web fallback: " + webFallbackUsed + ", best score: " + String.format(Locale.ROOT, "%.3f", bestRetrievalScore),
                    null,
                    new RAGObservabilityService.NodeMetrics(retrievedDocs.size(), webFallbackUsed)
            );

            return new KnowledgePacket(rewrittenQuery.fullQuery(), retrievedDocs, retrievedImages, context, imageContext, retrievalEvidence, webFallbackUsed);
        });
    }

    /**
     * 基于检索上下文执行回答评估（LLM 生成）。
     *
     * <p>该方法会将 {@link KnowledgePacket} 中的 context/evidence 注入提示词模板，并执行模型调用生成结构化评估 JSON。
     * 为了控制 token 成本，会对 context/evidence/profileSnapshot 做截断（truncate），同时保留 evidence 编号约束。</p>
     *
     * <p>异常/超时降级：</p>
     * <ul>
     *     <li>模型调用失败：返回 fallback 评估 JSON，并将 token 计数置 0</li>
     *     <li>上层入口 {@link #processAnswer} 还会额外做 citations/conflicts 的证据编号校验与修补</li>
     * </ul>
     *
     * @param topic           当前题目主题
     * @param question        当前问题
     * @param userAnswer      用户回答
     * @param difficultyLevel 难度等级
     * @param followUpState   追问状态
     * @param topicMastery    主题掌握度（画像侧传入）
     * @param profileSnapshot 画像快照（可能较长，会被截断）
     * @param strategyHint    策略提示（可选）
     * @param packet          知识包（包含 context 与证据目录）
     * @return 评估结果（content 为 JSON 字符串，含 citations/conflicts 等字段）
     */
    public EvaluationResult evaluateWithKnowledge(String topic, String question, String userAnswer, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot, String strategyHint, KnowledgePacket packet) {
        return executeWithinTraceRoot("KNOWLEDGE_EVAL", "Knowledge Evaluation", "Q: " + question, () -> {
            String originalContext = packet == null ? "" : packet.context();
            String originalImageContext = packet == null ? "" : packet.imageContext();
            String originalEvidence = packet == null ? "[]" : packet.retrievalEvidence();
            String finalContext = truncate(originalContext, 1400);
            String finalImageContext = truncate(originalImageContext, 600);
            String finalEvidence = truncate(originalEvidence, 900);
            String safeProfileSnapshot = truncate(profileSnapshot, 480);
            String normalizedStrategy = strategyHint == null ? "" : strategyHint.trim();
            if (observabilitySwitchProperties.isRagTraceEnabled()) {
                logger.info(
                        "评估调用参数: topic={}, difficulty={}, followUp={}, mastery={}, questionLen={}, answerLen={}, strategyLen={}, profileLen={}/{}, contextLen={}/{}, imageContextLen={}/{}, evidenceLen={}/{}, evidenceCount={}, retrievedDocs={}, webFallbackUsed={}",
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
                        safeLength(finalImageContext),
                        safeLength(originalImageContext),
                        safeLength(finalEvidence),
                        safeLength(originalEvidence),
                        parseEvidenceCatalog(originalEvidence).size(),
                        packet == null || packet.retrievedDocs() == null ? 0 : packet.retrievedDocs().size(),
                        packet != null && packet.webFallbackUsed()
                );
            }
            try {
                RoutingChatService.RoutingResult routingResult = callWithRetryResult(() -> generateEvaluationResult(topic, question, userAnswer, difficultyLevel, followUpState, topicMastery, safeProfileSnapshot, finalContext, finalImageContext, finalEvidence, normalizedStrategy), 2, "回答评估");
                return new EvaluationResult(routingResult.content(), routingResult.inputTokens(), routingResult.outputTokens());
            } catch (RuntimeException e) {
                logger.warn("回答评估失败，返回降级结果。原因: {}", summarizeError(e));
                return new EvaluationResult(buildFallbackEvaluation(question, e), 0, 0);
            }
        });
    }

    /**
     * 在缺少父节点时自动补齐一层 Trace 根节点，避免链路被拆成多个孤立片段。
     *
     * <p>如果当前线程已经处于某个 Trace 节点内部，则直接复用现有父节点，
     * 不再额外创建新根节点；只有在直接调用入口方法时才会自动包一层根节点。</p>
     *
     * @param nodeType 根节点类型
     * @param nodeName 根节点名称
     * @param inputSummary 根节点输入摘要
     * @param action 需要在根节点内执行的动作
     * @return 动作执行结果
     * @param <T> 返回值类型
     */
    private <T> T executeWithinTraceRoot(String nodeType, String nodeName, String inputSummary, Supplier<T> action) {
        String currentNodeId = RAGTraceContext.getCurrentNodeId();
        if (currentNodeId != null && !currentNodeId.isBlank()) {
            return action.get();
        }
        String traceId = RAGTraceContext.getTraceId();
        String rootNodeId = UUID.randomUUID().toString();
        observabilityService.startNode(traceId, rootNodeId, null, nodeType, nodeName);
        try {
            T result = action.get();
            observabilityService.endNode(traceId, rootNodeId, inputSummary, nodeName + " completed", null);
            return result;
        } catch (RuntimeException e) {
            observabilityService.endNode(traceId, rootNodeId, inputSummary, null, summarizeError(e));
            throw e;
        }
    }

    private RoutingChatService.RoutingResult generateEvaluationResult(String topic, String question, String userAnswer, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot, String context, String imageContext, String retrievalEvidence, String strategyHint) {
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
            contextMap.put("imageContext", imageContext);
            contextMap.put("retrievalEvidence", retrievalEvidence);
            contextMap.put("cases", cases);
            contextMap.put("question", question);
            contextMap.put("userAnswer", userAnswer);

            PromptManager.PromptPair pair = promptManager.renderSplit("interviewer", "evaluation", contextMap);
            RoutingChatService.RoutingResult result = callWithRetryResult(() ->
                routingChatService.callWithMetadata(pair.systemPrompt(), pair.userPrompt(), ModelRouteType.THINKING, "回答评估"),
                1, "回答评估");
            
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
    private List<Document> retrieveHybridDocuments(RewrittenQuery query, int topK) {
        List<String> intentFocusTerms = buildIntentFocusTerms(query.coreTerms());
        // 通道 A：意图定向检索（利用词法索引服务进行高精度标签/路径匹配）
        java.util.concurrent.CompletableFuture<List<Document>> intentDirectedFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                List<Document> docs = lexicalIndexService.searchIntentDirected(query.coreTerms(), intentFocusTerms, Math.max(topK + 3, 8));
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
                        SearchRequest.builder().query(query.fullQuery()).topK(Math.max(topK + 3, 8)).build()
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
                List<String> queryTokens = intentFocusTerms.isEmpty() ? lexicalIndexService.tokenize(query.coreTerms()) : intentFocusTerms;
                for (String token : queryTokens) {
                    // 这里先兼容旧的概念名拼装语句，随后统一覆写为描述型 GraphRAG 上下文。
                    List relatedConcepts =
                            techConceptRepository.findRelatedConceptSnippetsWithinTwoHops(token);
                    if (relatedConcepts != null && !relatedConcepts.isEmpty()) {
                        String graphContext = "知识图谱关联提示：与【" + token + "】存在深度技术关联的概念包括 -> " + String.join(", ", relatedConcepts);
                        graphContext = buildGraphConceptContext(token, relatedConcepts);
                        if (graphContext.isBlank()) {
                            continue;
                        }
                        Document doc = new Document(graphContext);
                        doc.getMetadata().put("source_type", "graph_rag");
                        doc.getMetadata().put("retrieve_channel", "graph_rag");
                        doc.getMetadata().put("graph_anchor", token);
                        doc.getMetadata().put("evidence_snippet", truncate(graphContext, 90));
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
        List<Document> hydrated = maybeHydrateParentDocuments(fused, topK);
        return rerankByQueryOverlap(query, hydrated, topK);
    }

    /**
     * 根据配置判断是否需要触发 Web fallback。
     *
     * @param allowWebFallback 当前调用方是否允许使用 Web fallback
     * @param retrievalQuery 重写后的检索词
     * @param retrievedDocs 本地检索结果
     * @param context 本地拼接上下文
     * @param bestRetrievalScore 本地结果最佳重排分数
     * @return true 表示需要使用 Web fallback
     */
    private boolean shouldUseWebFallback(
            boolean allowWebFallback,
            String retrievalQuery,
            List<Document> retrievedDocs,
            String context,
            double bestRetrievalScore
    ) {
        if (!allowWebFallback) {
            return false;
        }
        boolean emptyContext = context == null || context.isBlank() || retrievedDocs == null || retrievedDocs.isEmpty();
        RagRetrievalProperties.WebFallbackMode mode = ragRetrievalProperties.getWebFallbackMode();
        return switch (mode) {
            case NONE -> false;
            case ON_EMPTY -> emptyContext;
            case ON_LOW_QUALITY -> emptyContext || bestRetrievalScore < ragRetrievalProperties.getWebFallbackQualityThreshold();
        };
    }

    /**
     * 计算本地检索结果中最佳的一条重排分数，用作低质量 fallback 的判定依据。
     *
     * @param query 查询文本
     * @param docs 本地检索结果
     * @return 最高分；若没有结果则返回 0
     */
    private double bestRetrievalScore(String query, List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return 0.0D;
        }
        List<String> queryTokens = retrievalTokenizerService.tokenize(query);
        if (queryTokens.isEmpty()) {
            return 0.0D;
        }
        double bestScore = 0.0D;
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            List<String> docTokens = retrievalTokenizerService.tokenize(doc.getText());
            long overlap = queryTokens.stream().filter(docTokens::contains).count();
            double overlapRatio = docTokens.isEmpty()
                    ? 0.0D
                    : (double) overlap / (double) Math.min(queryTokens.size(), Math.max(1, docTokens.size()));
            double score = (overlapRatio * 0.7D + (1.0D / (i + 1)) * 0.3D) * sourceTypeBoost(doc);
            bestScore = Math.max(bestScore, score);
        }
        return bestScore;
    }

    /**
     * 针对 Parent-Child 检索结果做“父文上下文 + 子块命中片段”回填。
     *
     * <p>这里不再直接用整段 parent 覆盖 child，而是保留局部命中片段，
     * 再补上一段围绕命中位置的 parent 上下文，避免语义被整段长文稀释。</p>
     *
     * @param docs 融合后的候选文档
     * @param topK 当前链路期望返回的结果条数
     * @return 完成拼接回填后的候选文档
     */
    private List<Document> maybeHydrateParentDocuments(List<Document> docs, int topK) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        if (!parentChildRetrievalProperties.isEnabled()) {
            return docs;
        }
        int hydrateLimit = Math.max(1, Math.min(parentChildRetrievalProperties.getHydrateParentTopN(), Math.max(1, topK)));
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
            ParentChildHydrationPayload payload = buildParentChildHydrationPayload(doc, parent);
            Document hydrated = new Document(payload.hydratedText());
            hydrated.getMetadata().putAll(doc.getMetadata());
            hydrated.getMetadata().put("parent_id", parent.getParentId());
            hydrated.getMetadata().put("parent_source", parent.getFilePath());
            hydrated.getMetadata().put("file_path", parent.getFilePath());
            hydrated.getMetadata().put("section_path", parent.getSectionPath());
            hydrated.getMetadata().put("knowledge_tags", parent.getKnowledgeTags());
            hydrated.getMetadata().put("source_type", parent.getSourceType());
            hydrated.getMetadata().put("hydration_mode", "parent_child_concat");
            hydrated.getMetadata().put("parent_context_excerpt", payload.parentContextExcerpt());
            hydrated.getMetadata().put("child_match_excerpt", payload.childMatchExcerpt());
            hydrated.getMetadata().put("evidence_snippet", payload.evidenceSnippet());
            result.add(hydrated);
        }
        return result;
    }

    /**
     * 构建 parent-child 回填载荷，统一生成最终上下文与证据摘要。
     *
     * @param doc 原始 child 文档
     * @param parent parent 文档信息
     * @return 结构化回填结果
     */
    private ParentChildHydrationPayload buildParentChildHydrationPayload(Document doc, com.example.interview.entity.RagParentDO parent) {
        String childMatchExcerpt = extractChildMatchExcerpt(doc == null ? "" : doc.getText());
        String parentContextExcerpt = extractParentContextExcerpt(parent == null ? "" : parent.getParentText(), childMatchExcerpt);
        String hydratedText = composeHydratedText(parent, parentContextExcerpt, childMatchExcerpt);
        String evidenceSnippet = buildHydratedEvidenceSnippet(parentContextExcerpt, childMatchExcerpt);
        return new ParentChildHydrationPayload(hydratedText, parentContextExcerpt, childMatchExcerpt, evidenceSnippet);
    }

    /**
     * 从 child 文本中抽取真正命中的正文片段。
     *
     * <p>chunk 文本前面可能带有文档名、章节名、标签等前缀，
     * 这里优先剥离这类元数据，避免 evidence 被装饰性信息占满。</p>
     *
     * @param childText 原始 child 文本
     * @return 归一化后的命中片段
     */
    private String extractChildMatchExcerpt(String childText) {
        if (childText == null || childText.isBlank()) {
            return "";
        }
        String normalized = childText.replace("\r\n", "\n").trim();
        String[] parts = normalized.split("\\n", 2);
        String candidate = parts.length >= 2 ? parts[1] : normalized;
        candidate = candidate.replaceFirst("^(\\[[^\\]]+\\]\\s*)+", "");
        candidate = normalizeSnippet(candidate);
        return truncate(candidate, Math.max(60, parentChildRetrievalProperties.getHydrateChildMatchChars()));
    }

    /**
     * 截取围绕命中片段的 parent 上下文窗口。
     *
     * <p>如果能在 parent 中定位到 child 片段，就按锚点附近截取；
     * 如果定位失败，则退化为 parent 头部摘要，保证链路稳定。</p>
     *
     * @param parentText parent 全量文本
     * @param childMatchExcerpt child 命中片段
     * @return parent 上下文摘要
     */
    private String extractParentContextExcerpt(String parentText, String childMatchExcerpt) {
        String normalizedParent = normalizeSnippet(parentText);
        if (normalizedParent.isBlank()) {
            return "";
        }
        int maxChars = Math.max(160, parentChildRetrievalProperties.getHydrateParentContextChars());
        String anchor = resolveParentAnchor(normalizedParent, childMatchExcerpt);
        if (anchor.isBlank()) {
            return truncate(normalizedParent, maxChars);
        }
        int anchorStart = normalizedParent.indexOf(anchor);
        if (anchorStart < 0) {
            return truncate(normalizedParent, maxChars);
        }
        return excerptAroundRange(normalizedParent, anchorStart, anchorStart + anchor.length(), maxChars);
    }

    /**
     * 通过 child 前缀递减匹配的方式，为 parent 文本找到可定位的锚点。
     *
     * @param parentText 归一化后的 parent 文本
     * @param childMatchExcerpt child 命中片段
     * @return 可定位锚点；若未找到则返回空字符串
     */
    private String resolveParentAnchor(String parentText, String childMatchExcerpt) {
        String normalizedChild = normalizeSnippet(childMatchExcerpt);
        if (normalizedChild.isBlank()) {
            return "";
        }
        int maxLength = Math.min(80, normalizedChild.length());
        for (int length = maxLength; length >= 18; length -= 10) {
            String candidate = normalizedChild.substring(0, length);
            if (parentText.contains(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    /**
     * 围绕命中位置截取固定大小的上下文窗口，并用省略号提示裁剪边界。
     *
     * @param text 原始文本
     * @param start 命中起始位置
     * @param end 命中结束位置
     * @param maxChars 最长保留字符数
     * @return 裁剪后的上下文摘要
     */
    private String excerptAroundRange(String text, int start, int end, int maxChars) {
        if (text == null || text.isBlank() || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        int safeStart = Math.max(0, start - maxChars / 3);
        int safeEnd = Math.min(text.length(), safeStart + maxChars);
        if (safeEnd < end) {
            safeEnd = Math.min(text.length(), end + maxChars / 3);
            safeStart = Math.max(0, safeEnd - maxChars);
        }
        String excerpt = text.substring(safeStart, safeEnd).trim();
        if (safeStart > 0) {
            excerpt = "..." + excerpt;
        }
        if (safeEnd < text.length()) {
            excerpt = excerpt + "...";
        }
        return excerpt;
    }

    /**
     * 将 parent 上下文与 child 命中片段拼成最终写回 RAG 的文本。
     *
     * @param parent parent 元数据
     * @param parentContextExcerpt parent 摘要
     * @param childMatchExcerpt child 摘要
     * @return 拼接后的文本
     */
    private String composeHydratedText(com.example.interview.entity.RagParentDO parent, String parentContextExcerpt, String childMatchExcerpt) {
        StringBuilder builder = new StringBuilder();
        if (parent != null && parent.getSectionPath() != null && !parent.getSectionPath().isBlank()) {
            builder.append("\u3010\u7AE0\u8282\u8DEF\u5F84\u3011").append(parent.getSectionPath().trim()).append("\n");
        }
        if (parentContextExcerpt != null && !parentContextExcerpt.isBlank()) {
            builder.append("\u3010\u7236\u6587\u4E0A\u4E0B\u6587\u3011").append(parentContextExcerpt.trim());
        }
        if (childMatchExcerpt != null && !childMatchExcerpt.isBlank()) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append("\u3010\u547D\u4E2D\u7247\u6BB5\u3011").append(childMatchExcerpt.trim());
        }
        return builder.toString().trim();
    }

    /**
     * 生成证据目录优先展示的摘要文本。
     *
     * @param parentContextExcerpt parent 摘要
     * @param childMatchExcerpt child 摘要
     * @return 便于 citations 复用的单行摘要
     */
    private String buildHydratedEvidenceSnippet(String parentContextExcerpt, String childMatchExcerpt) {
        String contextPart = truncate(normalizeSnippet(parentContextExcerpt), 48);
        String matchPart = truncate(normalizeSnippet(childMatchExcerpt), 36);
        if (!matchPart.isBlank() && !contextPart.isBlank()) {
            return "\u547D\u4E2D=" + matchPart + " | \u4E0A\u6587=" + contextPart;
        }
        if (!matchPart.isBlank()) {
            return "\u547D\u4E2D=" + matchPart;
        }
        return contextPart;
    }

    /**
     * 统一做空白字符归一化，减少换行噪音对重排和 evidence 的影响。
     *
     * @param text 原始文本
     * @return 单行化后的文本
     */
    private String normalizeSnippet(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * 将图谱关联概念摘要拼装成更适合 RAG 消费的自然语言证据。
     *
     * @param anchorConcept 当前查询命中的锚点概念
     * @param relatedConcepts 图谱返回的关联概念摘要
     * @return 描述性图谱上下文；若无有效内容则返回空字符串
     */
    private String buildGraphConceptContext(String anchorConcept, List<com.example.interview.graph.TechConceptSnippetView> relatedConcepts) {
        if (relatedConcepts == null || relatedConcepts.isEmpty()) {
            return "";
        }
        String relatedSummary = relatedConcepts.stream()
                .filter(Objects::nonNull)
                .map(this::formatGraphConceptSnippet)
                .filter(item -> item != null && !item.isBlank())
                .limit(5)
                .collect(Collectors.joining("；"));
        if (relatedSummary.isBlank()) {
            return "";
        }
        return "知识图谱关联提示：围绕「" + anchorConcept + "」可继续延展的概念包括：" + relatedSummary;
    }

    /**
     * 将单个图谱概念整理为“名称 + 类型 + 描述”的紧凑片段。
     *
     * @param concept 图谱概念摘要
     * @return 单个概念的可读文本
     */
    private String formatGraphConceptSnippet(com.example.interview.graph.TechConceptSnippetView concept) {
        if (concept == null) {
            return "";
        }
        String name = concept.getName() == null ? "" : concept.getName().trim();
        String type = concept.getType() == null ? "" : concept.getType().trim();
        String description = truncate(normalizeSnippet(concept.getDescription()), 60);
        if (name.isBlank() && description.isBlank()) {
            return "";
        }
        if (!name.isBlank() && !type.isBlank() && !description.isBlank()) {
            return name + "（" + type + "）: " + description;
        }
        if (!name.isBlank() && !description.isBlank()) {
            return name + ": " + description;
        }
        if (!name.isBlank() && !type.isBlank()) {
            return name + "（" + type + "）";
        }
        return name.isBlank() ? description : name;
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
        return lexicalIndexService.tokenize(retrievalQuery).stream()
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

    private List<Document> rerankByQueryOverlap(RewrittenQuery query, List<Document> docs, int topK) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        List<String> coreTokens = lexicalIndexService.tokenize(query.coreTerms());
        List<String> expandTokens = query.expandTerms().isBlank()
                ? List.of()
                : lexicalIndexService.tokenize(query.expandTerms());
        if (coreTokens.isEmpty() && expandTokens.isEmpty()) {
            return docs.stream().limit(topK).collect(Collectors.toList());
        }
        int totalTokens = coreTokens.size() + expandTokens.size();
        Map<Document, Double> scoreMap = new HashMap<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            List<String> docTokens = lexicalIndexService.tokenize(doc.getText());
            long coreHits = coreTokens.stream().filter(docTokens::contains).count();
            long expandHits = expandTokens.stream().filter(docTokens::contains).count();
            double weightedOverlap = totalTokens == 0 ? 0.0
                    : (coreHits * 1.0 + expandHits * 0.4) / totalTokens;
            double score = (weightedOverlap * 0.7 + (1.0 / (i + 1)) * 0.3) * sourceTypeBoost(doc);
            doc.getMetadata().put("retrieval_score", score);
            scoreMap.put(doc, score);
        }
        return docs.stream()
                .sorted((a, b) -> Double.compare(scoreMap.getOrDefault(b, 0.0), scoreMap.getOrDefault(a, 0.0)))
                .limit(topK)
                .collect(Collectors.toList());
    }

    private boolean containsVisualIntent(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        return VISUAL_INTENT_KEYWORDS.stream().anyMatch(normalized::contains);
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
            prompt, ModelRouteType.GENERAL, TimeoutHint.NORMAL, "关键词提取"
        );
        return parseRewrittenQuery(raw);
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
            String text = metadata != null
                    && metadata.get("evidence_snippet") != null
                    && !String.valueOf(metadata.get("evidence_snippet")).isBlank()
                    ? String.valueOf(metadata.get("evidence_snippet"))
                    : normalizeSnippet(doc.getText());
            text = truncate(text, 90);
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

    /**
     * Parent-Child 回填后的结构化载荷。
     *
     * @param hydratedText 最终写回文档正文的拼接文本
     * @param parentContextExcerpt parent 上下文摘要
     * @param childMatchExcerpt child 命中片段摘要
     * @param evidenceSnippet 证据目录优先展示的单行摘要
     */
    private record ParentChildHydrationPayload(
            String hydratedText,
            String parentContextExcerpt,
            String childMatchExcerpt,
            String evidenceSnippet
    ) {
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
            logger.debug("[generateFirstQuestion] Params: topic={}, skipIntro={}", topic, skipIntro);
            Map<String, Object> vars = new HashMap<>();
            vars.put("skillBlock", skillBlock);
            vars.put("resume", truncate(resumeContent, 1500));
            vars.put("profileSnapshot", truncate(profileSnapshot, 500));
            vars.put("topic", topic == null ? "" : topic);
            vars.put("skipIntro", skipIntro);
            PromptManager.PromptPair pair = promptManager.renderSplit("interviewer", "first-question", vars);
            String rawQuestion = routingChatService.callWithFirstPacketProbeSupplier(
                    () -> buildFallbackFirstQuestion(topic),
                    pair.systemPrompt(),
                    pair.userPrompt(),
                    ModelRouteType.GENERAL,
                    TimeoutHint.NORMAL,
                    "首题生成"
            );
            logger.debug("[generateFirstQuestion] Response length={}", rawQuestion == null ? 0 : rawQuestion.length());
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
        PromptManager.PromptPair pair = promptManager.renderSplit("interviewer", "final-report", params);

        try {
            logger.debug("[generateFinalReport] Prompt length={}", pair.userPrompt().length());
            String response = callWithRetry(() -> routingChatService.call(
                pair.systemPrompt(), pair.userPrompt(), ModelRouteType.THINKING, "最终复盘"
            ), 1, "最终复盘");
            logger.debug("====== [RAGService - generateFinalReport] Response ======");
            logger.debug("{}", response);
            return response;
        } catch (RuntimeException e) {
            logger.warn("最终复盘生成失败，返回降级报告。原因: {}", summarizeError(e));
            return buildFallbackReport(history, targetedSuggestion);
        }
    }

    public String generateCodingQuestion(String topic, String difficulty, String profileSnapshot) {
        String normalizedTopic = topic == null || topic.isBlank() ? "数组与字符串" : topic.trim();
        String normalizedDifficulty = difficulty == null || difficulty.isBlank() ? "medium" : difficulty.trim().toLowerCase(Locale.ROOT);
        String normalizedQuestionType = normalizeCodingQuestionType(normalizedTopic);
        String skillBlock = resolveCodingSkillBlock(normalizedQuestionType);
        
        Map<String, Object> params = new HashMap<>();
        params.put("skillBlock", skillBlock);
        params.put("topic", normalizedTopic);
        params.put("difficulty", normalizedDifficulty);
        params.put("questionType", normalizedQuestionType);
        params.put("profileSnapshot", truncate(profileSnapshot, 240));
        PromptManager.PromptPair pair = promptManager.renderSplit("coding-coach", "coding-question", params);

        try {
            logger.debug("====== [RAGService - generateCodingQuestion] Prompt Template ======");
            logger.debug("{}", pair.userPrompt());
            String raw = callWithRetry(() -> routingChatService.callWithFirstPacketProbeSupplier(
                () -> "",
                pair.systemPrompt(), pair.userPrompt(), ModelRouteType.GENERAL, TimeoutHint.NORMAL, "刷题题目生成"
            ), 1, "刷题题目生成");
            logger.debug("====== [RAGService - generateCodingQuestion] Response ======");
            logger.debug("{}", raw);
            if (raw == null || raw.isBlank()) {
                return buildFallbackCodingQuestion(normalizedTopic, normalizedDifficulty, normalizedQuestionType);
            }
            return truncate(raw.replace("\r\n", " ").replaceAll("\\s+", " ").trim(), 1200);
        } catch (RuntimeException e) {
            logger.warn("刷题题目生成失败，返回降级题目。原因: {}", summarizeError(e));
            return buildFallbackCodingQuestion(normalizedTopic, normalizedDifficulty, normalizedQuestionType);
        }
    }

    /**
     * 批量生成选择题，返回结构化 QuizQuestion 列表。
     * 使用单次 LLM 调用生成 N 道题目。
     */
    public List<CodingPracticeAgent.QuizQuestion> generateBatchQuiz(
            String topic, String difficulty, int count, String profileSnapshot) {
        String normalizedTopic = topic == null || topic.isBlank() ? "Java基础" : topic.trim();
        String normalizedDifficulty = difficulty == null || difficulty.isBlank() ? "medium" : difficulty.trim().toLowerCase(Locale.ROOT);
        String skillBlock = resolveCodingSkillBlock("选择题");

        Map<String, Object> params = new HashMap<>();
        params.put("skillBlock", skillBlock);
        params.put("topic", normalizedTopic);
        params.put("difficulty", normalizedDifficulty);
        params.put("count", String.valueOf(Math.min(count, 10)));
        params.put("questionType", "选择题");
        params.put("profileSnapshot", truncate(profileSnapshot, 240));

        // 使用 batch-quiz-question 模板；如果不存在则用内联 prompt
        String sp;
        String up;
        try {
            PromptManager.PromptPair pair = promptManager.renderSplit("coding-coach", "batch-quiz-question", params);
            sp = pair.systemPrompt();
            up = pair.userPrompt();
        } catch (Exception e) {
            // 模板不存在时使用内联 prompt
            sp = "你是一位专业的技术面试出题官。";
            up = buildInlineBatchQuizPrompt(normalizedTopic, normalizedDifficulty, count);
        }

        final String finalSystemPrompt = sp;
        final String finalUserPrompt = up;

        try {
            String raw = callWithRetry(() -> routingChatService.callWithFirstPacketProbeSupplier(
                () -> "[]",
                finalSystemPrompt, finalUserPrompt, ModelRouteType.GENERAL, TimeoutHint.NORMAL, "批量选择题生成"
            ), 1, "批量选择题生成");

            if (raw == null || raw.isBlank()) {
                return fallbackBatchQuiz(normalizedTopic, normalizedDifficulty, count, profileSnapshot);
            }

            // 清理 markdown 代码块标记
            String clean = raw.replaceAll("```json", "").replaceAll("```", "").trim();
            // 如果包含 [ 则定位到 JSON 数组
            int arrayStart = clean.indexOf('[');
            int arrayEnd = clean.lastIndexOf(']');
            if (arrayStart >= 0 && arrayEnd > arrayStart) {
                clean = clean.substring(arrayStart, arrayEnd + 1);
            }

            List<CodingPracticeAgent.QuizQuestion> questions = objectMapper.readValue(clean,
                objectMapper.getTypeFactory().constructCollectionType(List.class, CodingPracticeAgent.QuizQuestion.class));

            if (questions == null || questions.isEmpty()) {
                return fallbackBatchQuiz(normalizedTopic, normalizedDifficulty, count, profileSnapshot);
            }

            // 确保 index 正确
            List<CodingPracticeAgent.QuizQuestion> result = new java.util.ArrayList<>();
            for (int i = 0; i < questions.size(); i++) {
                CodingPracticeAgent.QuizQuestion q = questions.get(i);
                result.add(new CodingPracticeAgent.QuizQuestion(
                    i + 1, q.stem(), q.options(), q.correctAnswer(), q.explanation()));
            }
            return result;
        } catch (Exception e) {
            logger.warn("批量选择题生成失败，降级逐题生成。原因: {}", summarizeError(e));
            return fallbackBatchQuiz(normalizedTopic, normalizedDifficulty, count, profileSnapshot);
        }
    }

    /**
     * 降级方案：逐题调用 generateCodingQuestion 生成（无正确答案和解析）。
     */
    private List<CodingPracticeAgent.QuizQuestion> fallbackBatchQuiz(
            String topic, String difficulty, int count, String profileSnapshot) {
        List<CodingPracticeAgent.QuizQuestion> result = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            String questionText = generateCodingQuestion(topic + "（选择题）", difficulty, profileSnapshot);
            result.add(new CodingPracticeAgent.QuizQuestion(
                i + 1, questionText, List.of("A. 请参考题目", "B. 请参考题目", "C. 请参考题目", "D. 请参考题目"),
                "A", "该题目由降级方案生成，暂无解析。请根据题干自行判断。"));
        }
        return result;
    }

    /**
     * 内联 prompt（当 batch-quiz-question 模板不存在时使用）。
     */
    private String buildInlineBatchQuizPrompt(String topic, String difficulty, int count) {
        return "请生成 " + count + " 道关于「" + topic + "」的" + difficulty + "难度选择题。\n\n"
            + "严格按以下 JSON 格式输出（不要输出其他内容）：\n"
            + "```json\n"
            + "[\n"
            + "  {\n"
            + "    \"index\": 1,\n"
            + "    \"stem\": \"题目描述\",\n"
            + "    \"options\": [\"A. 选项1\", \"B. 选项2\", \"C. 选项3\", \"D. 选项4\"],\n"
            + "    \"correctAnswer\": \"B\",\n"
            + "    \"explanation\": \"详细解析\"\n"
            + "  }\n"
            + "]\n"
            + "```\n"
            + "要求：\n"
            + "1. 每题必须有4个选项（A/B/C/D）\n"
            + "2. correctAnswer 只能是 A/B/C/D 中的一个字母\n"
            + "3. explanation 要详细解释为什么选择该答案\n"
            + "4. 题目要有区分度，难度符合 " + difficulty + " 级别\n"
            + "5. 只输出 JSON 数组，不要有多余文字";
    }

    public CodingAssessment evaluateCodingAnswer(String topic, String difficulty, String question, String answer) {
        String normalizedTopic = topic == null || topic.isBlank() ? "算法" : topic.trim();
        String normalizedDifficulty = difficulty == null || difficulty.isBlank() ? "medium" : difficulty.trim().toLowerCase(Locale.ROOT);
        String safeQuestion = question == null ? "" : question.trim();
        String safeAnswer = answer == null ? "" : answer.trim();
        if (safeAnswer.isBlank()) {
            return new CodingAssessment(20, "未提供有效答案。", "先补充完整思路，再给出复杂度分析。", fallbackNextCodingQuestion(normalizedTopic, 20, normalizeCodingQuestionType(normalizedTopic)));
        }
        String normalizedQuestionType = normalizeCodingQuestionType(normalizedTopic);
        String skillBlock = resolveCodingSkillBlock(normalizedQuestionType);
        
        Map<String, Object> params = new HashMap<>();
        params.put("skillBlock", skillBlock);
        params.put("topic", normalizedTopic);
        params.put("difficulty", normalizedDifficulty);
        params.put("questionType", normalizedQuestionType);
        params.put("question", truncate(safeQuestion, 1200));
        params.put("answer", truncate(safeAnswer, 800));
        PromptManager.PromptPair pair = promptManager.renderSplit("coding-coach", "coding-evaluation", params);

        try {
            logger.debug("====== [RAGService - evaluateCodingAnswer] Prompt Template ======");
            logger.debug("{}", pair.userPrompt());
            String raw = callWithRetry(() -> routingChatService.callWithFirstPacketProbeSupplier(
                () -> "{\"score\":0,\"feedback\":\"评估超时\"}",
                pair.systemPrompt(), pair.userPrompt(), ModelRouteType.THINKING, TimeoutHint.SLOW, "刷题答案评估"
            ), 1, "刷题答案评估");
            logger.debug("====== [RAGService - evaluateCodingAnswer] Response ======");
            logger.debug("{}", raw);
            String clean = normalizeJsonContent(raw);
            JsonNode node = objectMapper.readTree(clean);
            int score = node.path("score").asInt(0);
            String feedback = node.path("feedback").asText("建议补充完整思路并说明复杂度。");
            String nextHint = node.path("nextHint").asText("请优先补充边界条件与复杂度分析。");
            String nextQuestion = node.path("nextQuestion").asText("");
            if (nextQuestion == null || nextQuestion.isBlank()) {
                nextQuestion = generateNextCodingQuestion(normalizedTopic, normalizedDifficulty, safeQuestion, safeAnswer, score);
            }
            return new CodingAssessment(Math.max(0, Math.min(score, 100)), feedback, nextHint, nextQuestion == null ? "" : nextQuestion);
        } catch (Exception e) {
            logger.warn("刷题答案评估失败，返回降级评分。原因: {}", summarizeError(e));
            return fallbackCodingAssessment(safeAnswer, normalizedQuestionType, normalizedTopic);
        }
    }

    public String generateNextCodingQuestion(String topic, String difficulty, String question, String answer, int score) {
        String normalizedQuestionType = normalizeCodingQuestionType(topic);
        String skillBlock = resolveCodingSkillBlock(normalizedQuestionType);
        
        Map<String, Object> params = new HashMap<>();
        params.put("skillBlock", skillBlock);
        params.put("topic", topic == null ? "算法" : topic);
        params.put("difficulty", difficulty == null ? "medium" : difficulty);
        params.put("questionType", normalizedQuestionType);
        params.put("question", truncate(question, 240));
        params.put("answer", truncate(answer, 500));
        params.put("score", String.valueOf(score));
        PromptManager.PromptPair pair = promptManager.renderSplit("coding-coach", "coding-next-question", params);

        try {
            String raw = callWithRetry(() -> routingChatService.callWithFirstPacketProbeSupplier(
                () -> "",
                pair.systemPrompt(), pair.userPrompt(), ModelRouteType.GENERAL, TimeoutHint.NORMAL, "刷题下一题生成"
            ), 1, "刷题下一题生成");
            if (raw == null || raw.isBlank()) {
                return fallbackNextCodingQuestion(topic, score, normalizedQuestionType);
            }
            return truncate(raw.replace("\r\n", " ").replaceAll("\\s+", " ").trim(), 1200);
        } catch (RuntimeException e) {
            logger.warn("刷题下一题生成失败，返回降级问题。原因: {}", summarizeError(e));
            return fallbackNextCodingQuestion(topic, score, normalizedQuestionType);
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
        PromptManager.PromptPair pair = promptManager.renderSplit("interviewer", "learning-plan", params);

        try {
            String raw = callWithRetry(() -> routingChatService.callWithFirstPacketProbeSupplier(
                () -> "",
                pair.systemPrompt(), pair.userPrompt(), ModelRouteType.GENERAL, TimeoutHint.NORMAL, "学习计划生成"
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

    private String buildFallbackCodingQuestion(String topic, String difficulty, String questionType) {
        if ("CHOICE".equals(questionType)) {
            return "请生成一道" + difficulty + "难度的「" + topic + "」选择题，包含 4 个选项，不要输出答案。";
        }
        if ("FILL".equals(questionType)) {
            return "请生成一道" + difficulty + "难度的「" + topic + "」填空题，给出题干与必要约束，不要输出答案。";
        }
        if ("SCENARIO".equals(questionType)) {
            return "请生成一道" + difficulty + "难度的「" + topic + "」场景题，要求贴近真实工程情境，不要输出答案。";
        }
        return "请完成一道" + difficulty + "难度的「" + topic + "」算法题：给定整数数组与目标值，返回两数之和等于目标值的下标，并说明时间复杂度与空间复杂度。";
    }

    private String fallbackNextCodingQuestion(String topic, int score, String questionType) {
        String safeTopic = topic == null || topic.isBlank() ? "数组与字符串" : topic;
        if ("CHOICE".equals(questionType)) {
            return "请继续生成一道「" + safeTopic + "」选择题，难度参考当前得分，包含 4 个选项且不要输出答案。";
        }
        if ("FILL".equals(questionType)) {
            return "请继续生成一道「" + safeTopic + "」填空题，难度参考当前得分，给出题干与必要约束。";
        }
        if ("SCENARIO".equals(questionType)) {
            return "请继续生成一道「" + safeTopic + "」场景题，难度参考当前得分，聚焦真实工程情境。";
        }
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

    private CodingAssessment fallbackCodingAssessment(String answer, String questionType, String topic) {
        String lower = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        if (!"ALGORITHM".equals(questionType)) {
            int score = lower.length() > 40 ? 68 : 48;
            return new CodingAssessment(
                    score,
                    "答案已提交，但评估服务暂不可用。建议补充关键判断条件、约束与边界情况。",
                    "请补充你对题干条件的理解，并说明最核心的判断依据。",
                    fallbackNextCodingQuestion(topic, score, questionType)
            );
        }
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
        return new CodingAssessment(score, feedback, nextHint, fallbackNextCodingQuestion("算法", score, "ALGORITHM"));
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

        // 尝试 JSON 解析：如果 LLM 返回了 JSON 格式，直接提取 question 字段
        String trimmed = raw.trim();
        String jsonCandidate = trimmed;
        if (jsonCandidate.startsWith("```json")) {
            jsonCandidate = jsonCandidate.substring(7);
        } else if (jsonCandidate.startsWith("```")) {
            jsonCandidate = jsonCandidate.substring(3);
        }
        if (jsonCandidate.endsWith("```")) {
            jsonCandidate = jsonCandidate.substring(0, jsonCandidate.length() - 3);
        }
        jsonCandidate = jsonCandidate.trim();
        if (jsonCandidate.startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(jsonCandidate);
                String q = node.path("question").asText("");
                if (!q.isBlank()) {
                    return truncate(q.trim(), 200);
                }
            } catch (Exception ignored) {
                // JSON 解析失败，继续走文本提取逻辑
            }
        }

        String normalized = raw.replace("\r\n", "\n")
                .replace("**", "")
                .replace("`", "")
                .trim();
        String[] lines = normalized.split("\\n");

        // 第一轮：找含问号的行（排除答案/解析/元信息行）
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
            if (isMetaOrAnswerLine(candidate)) {
                continue;
            }
            if (candidate.contains("？") || candidate.contains("?")) {
                return truncateToQuestion(candidate);
            }
        }

        // 第二轮：取第一个非元信息、非答案的非空行
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
            if (isMetaOrAnswerLine(candidate)) {
                continue;
            }
            if (!candidate.isBlank()) {
                return truncate(candidate, 140);
            }
        }
        return buildFallbackFirstQuestion(topic);
    }

    /**
     * 判断是否为元信息行或答案/解析行，应从首题输出中过滤掉。
     */
    private boolean isMetaOrAnswerLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        // 元信息前缀
        if (line.startsWith("出题依据") || line.startsWith("策略提示") || line.startsWith("后续建议")) {
            return true;
        }
        // 答案/解析关键词（行首或包含）
        String[] answerKeywords = {"答案", "解答", "参考答案", "解析", "正确答案", "答：", "答:",
                "分析：", "分析:", "解题", "思路", "要点", "考察", "知识点"};
        for (String keyword : answerKeywords) {
            if (line.startsWith(keyword)) {
                return true;
            }
        }
        // 模拟对话格式：候选人/面试者/应聘者的回答行
        String[] roleKeywords = {"候选人：", "候选人:", "面试者：", "面试者:", "应聘者：", "应聘者:",
                "求职者：", "求职者:", "回答：", "回答:", "A：", "A:"};
        for (String keyword : roleKeywords) {
            if (line.startsWith(keyword)) {
                return true;
            }
        }
        // 模拟的后续题目标记（第二题、第三题、Q2、Q3 等）
        if (line.matches("^第[二三四五六七八九十\\d]+题[：:].*") || line.matches("^Q[2-9]\\d*[：:.].*")) {
            return true;
        }
        return false;
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

    private String resolveCodingSkillBlock(String questionType) {
        if ("ALGORITHM".equals(questionType)) {
            return safeSkillText(agentSkillService.resolveSkillBlock("coding-interview-coach"));
        }
        return "";
    }

    private String normalizeCodingQuestionType(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (normalized.contains("选择") || normalized.contains("choice") || normalized.contains("单选") || normalized.contains("多选")) {
            return "CHOICE";
        }
        if (normalized.contains("填空") || normalized.contains("fill") || normalized.contains("补全")) {
            return "FILL";
        }
        if (normalized.contains("场景") || normalized.contains("scenario")) {
            return "SCENARIO";
        }
        return "ALGORITHM";
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

    /**
     * 检索知识包（RAG 中的“证据载体”）。
     *
     * <p>该对象用于在“检索阶段”与“生成/评估阶段”之间传递必要的信息：</p>
     * <ul>
     *     <li>retrievalQuery：改写后的检索 query（用于观测与复现）</li>
     *     <li>retrievedDocs：本地召回的文档片段（用于后续 evidence/trace 细节展示）</li>
     *     <li>context：最终注入提示词的上下文（可能是本地拼接或 Web fallback 拼接）</li>
     *     <li>retrievalEvidence：带编号的证据目录（citations/conflicts 只能引用编号）</li>
     *     <li>webFallbackUsed：是否触发了 Web fallback（用于观测/评测口径区分）</li>
     * </ul>
     */
    public record KnowledgePacket(
            String retrievalQuery,
            List<Document> retrievedDocs,
            List<ImageService.ImageResult> retrievedImages,
            String context,
            String imageContext,
            String retrievalEvidence,
            boolean webFallbackUsed
    ) {
        public KnowledgePacket(String retrievalQuery,
                               List<Document> retrievedDocs,
                               String context,
                               String retrievalEvidence,
                               boolean webFallbackUsed) {
            this(retrievalQuery, retrievedDocs, List.of(), context, "", retrievalEvidence, webFallbackUsed);
        }
    }

    private String buildImageContext(List<ImageService.ImageResult> retrievedImages) {
        if (retrievedImages == null || retrievedImages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (ImageService.ImageResult image : retrievedImages) {
            builder.append("[图").append(index++).append("] ")
                    .append(image.summaryText() == null ? image.imageName() : image.summaryText())
                    .append(" - 来源: ").append(image.imageName())
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private List<ImageService.ImageResult> mergeImageResults(List<ImageService.ImageResult> associatedImages,
                                                             List<ImageService.ImageResult> semanticImages) {
        Map<String, ImageService.ImageResult> merged = new LinkedHashMap<>();
        if (semanticImages != null) {
            for (ImageService.ImageResult image : semanticImages) {
                merged.put(image.imageId(), image);
            }
        }
        if (associatedImages != null) {
            for (ImageService.ImageResult image : associatedImages) {
                merged.compute(image.imageId(), (key, existing) ->
                        existing == null || image.relevanceScore() > existing.relevanceScore() ? image : existing);
            }
        }
        List<ImageService.ImageResult> ranked = merged.values().stream()
                .sorted((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()))
                .limit(3)
                .toList();
        if (ranked.isEmpty()) {
            return ranked;
        }
        double topScore = ranked.getFirst().relevanceScore();
        if (topScore < FINAL_IMAGE_MIN_SCORE) {
            return List.of();
        }
        List<ImageService.ImageResult> filtered = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            ImageService.ImageResult image = ranked.get(i);
            if (i == 0) {
                filtered.add(image);
                continue;
            }
            if (image.relevanceScore() < SECOND_IMAGE_MIN_SCORE) {
                continue;
            }
            if (topScore - image.relevanceScore() > ADDITIONAL_IMAGE_SCORE_GAP) {
                continue;
            }
            filtered.add(image);
        }
        return filtered;
    }

    public record CodingAssessment(
            int score,
            String feedback,
            String nextHint,
            String nextQuestion
    ) {
    }
}
