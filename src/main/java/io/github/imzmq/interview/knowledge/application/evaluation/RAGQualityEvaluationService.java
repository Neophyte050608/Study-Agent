package io.github.imzmq.interview.knowledge.application.evaluation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.config.observability.ObservabilitySwitchProperties;
import io.github.imzmq.interview.entity.knowledge.RagQualityEvalCaseDO;
import io.github.imzmq.interview.entity.knowledge.RagQualityEvalRunDO;
import io.github.imzmq.interview.knowledge.application.RAGService;
import io.github.imzmq.interview.mapper.knowledge.RagQualityEvalCaseMapper;
import io.github.imzmq.interview.mapper.knowledge.RagQualityEvalRunMapper;
import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.observability.application.TraceAttributeSanitizer;
import io.github.imzmq.interview.chat.application.LlmJsonParser;
import io.github.imzmq.interview.chat.application.JsonResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * RAG 生成质量评测服务。
 */
@Service
public class RAGQualityEvaluationService {

    private static final int MAX_REPORT_HISTORY = 100;
    private static final String DEFAULT_DATASET_FILE = "rag_quality_ground_truth.json";
    private static final Map<String, String> DATASET_FILE_MAPPING = Map.of(
            "default", DEFAULT_DATASET_FILE,
            "baseline", "rag_quality_ground_truth_baseline.json",
            "advanced", "rag_quality_ground_truth_advanced.json",
            "project", "rag_quality_ground_truth_project.json"
    );

    private final RAGService ragService;
    private final RoutingChatService routingChatService;
    private final ObservabilitySwitchProperties observabilitySwitchProperties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final LlmJsonParser llmJsonParser;
    private final RagQualityEvalRunMapper ragQualityEvalRunMapper;
    private final RagQualityEvalCaseMapper ragQualityEvalCaseMapper;
    private final Executor ragRetrieveExecutor;

    @Value("${app.eval.rag-quality.engine:java}")
    private String evaluationEngine;

    private final Deque<QualityEvalReport> reportHistory = new ConcurrentLinkedDeque<>();
    private final Map<String, QualityEvalReport> reportDetailHistory = new ConcurrentHashMap<>();

    @Autowired
    public RAGQualityEvaluationService(
            RAGService ragService,
            RoutingChatService routingChatService,
            ObservabilitySwitchProperties observabilitySwitchProperties,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            LlmJsonParser llmJsonParser,
            @Nullable RagQualityEvalRunMapper ragQualityEvalRunMapper,
            @Nullable RagQualityEvalCaseMapper ragQualityEvalCaseMapper,
            @Qualifier("ragRetrieveExecutor") Executor ragRetrieveExecutor
    ) {
        this.ragService = ragService;
        this.routingChatService = routingChatService;
        this.observabilitySwitchProperties = observabilitySwitchProperties;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.llmJsonParser = llmJsonParser;
        this.ragQualityEvalRunMapper = ragQualityEvalRunMapper;
        this.ragQualityEvalCaseMapper = ragQualityEvalCaseMapper;
        this.ragRetrieveExecutor = ragRetrieveExecutor;
    }

    public RAGQualityEvaluationService(
            RAGService ragService,
            RoutingChatService routingChatService,
            ObservabilitySwitchProperties observabilitySwitchProperties,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            LlmJsonParser llmJsonParser,
            @Qualifier("ragRetrieveExecutor") Executor ragRetrieveExecutor
    ) {
        this(ragService, routingChatService, observabilitySwitchProperties, resourceLoader, objectMapper, llmJsonParser, null, null, ragRetrieveExecutor);
    }

    public QualityEvalReport runDefaultEval(String engine) {
        return runEvalByDataset(null, engine);
    }

    public QualityEvalReport runEvalByDataset(String dataset, String engine) {
        ensureEvalEnabled();
        String datasetFile = resolveDatasetFilename(dataset);
        try {
            InputStream inputStream = resourceLoader.getResource("classpath:eval/" + datasetFile).getInputStream();
            List<QualityEvalCase> cases = objectMapper.readValue(inputStream, new TypeReference<List<QualityEvalCase>>() {
            });
            return runCustomEval(cases, buildDatasetRunOptions(datasetFile, "default-benchmark", "RAG生成质量默认评测集"), engine);
        } catch (Exception ignored) {
            List<QualityEvalCase> cases = List.of(
                    new QualityEvalCase(
                            "Spring事务传播行为有哪些",
                            "Spring事务传播行为包括REQUIRED、SUPPORTS、MANDATORY、REQUIRES_NEW、NOT_SUPPORTED、NEVER和NESTED。",
                            List.of("REQUIRED", "SUPPORTS", "MANDATORY", "REQUIRES_NEW", "NESTED"),
                            "spring"
                    ),
                    new QualityEvalCase(
                            "Redis为什么快",
                            "Redis主要依赖内存访问、单线程事件循环、IO多路复用和高效数据结构实现低延迟。",
                            List.of("内存", "单线程", "IO多路复用", "高效数据结构"),
                            "redis"
                    ),
                    new QualityEvalCase(
                            "MySQL隔离级别有哪些",
                            "MySQL常见隔离级别包括读未提交、读已提交、可重复读和串行化。",
                            List.of("读未提交", "读已提交", "可重复读", "串行化"),
                            "mysql"
                    )
            );
            return runCustomEval(cases, new EvalRunOptions(datasetFile, datasetFile + "-fallback", "default-benchmark", Map.of(), "评测集缺失时的兜底样本"), engine);
        }
    }

    public QualityEvalReport runDefaultEval() {
        return runDefaultEval(null);
    }

    public List<EvalDatasetDefinition> listBuiltInDatasets() {
        ensureEvalEnabled();
        return List.of(
                new EvalDatasetDefinition("default", DEFAULT_DATASET_FILE, "默认全集", "完整生成质量黄金集"),
                new EvalDatasetDefinition("baseline", "rag_quality_ground_truth_baseline.json", "基础档", "基础问答质量评测"),
                new EvalDatasetDefinition("advanced", "rag_quality_ground_truth_advanced.json", "进阶档", "RAG/Agent 架构质量评测"),
                new EvalDatasetDefinition("project", "rag_quality_ground_truth_project.json", "项目档", "项目实战问答质量评测")
        );
    }

    public QualityEvalReport runCustomEval(List<QualityEvalCase> cases) {
        return runCustomEval(cases, new EvalRunOptions("manual", "manual-rag-quality-eval", "", Map.of(), ""));
    }

    public QualityEvalReport runCustomEval(List<QualityEvalCase> cases, EvalRunOptions options) {
        return runCustomEval(cases, options, null);
    }

    public QualityEvalReport runCustomEval(List<QualityEvalCase> cases, EvalRunOptions options, String engine) {
        ensureEvalEnabled();
        List<QualityEvalCase> normalizedCases = normalizeCases(cases);
        String runId = UUID.randomUUID().toString();
        String reportTimestamp = Instant.now().toString();

        EvalRunOptions safeOptions = options == null
                ? new EvalRunOptions("manual", "manual-rag-quality-eval", "", Map.of(), "")
                : options;
        String safeDatasetSource = EvaluationServiceHelper.normalizeText(safeOptions.datasetSource(), "manual");
        String safeRunLabel = EvaluationServiceHelper.normalizeText(safeOptions.runLabel(), safeDatasetSource + "-rag-quality-eval");
        String safeExperimentTag = EvaluationServiceHelper.normalizeText(safeOptions.experimentTag(), "");
        Map<String, Object> safeParameterSnapshot = new LinkedHashMap<>(EvaluationServiceHelper.safeMap(safeOptions.parameterSnapshot()));
        String resolvedEngine = resolveEngine(engine);
        safeParameterSnapshot.put("engine", resolvedEngine);
        String safeNotes = EvaluationServiceHelper.normalizeText(safeOptions.notes(), "");

        if (normalizedCases.isEmpty()) {
            QualityEvalReport emptyReport = new QualityEvalReport(
                    runId,
                    reportTimestamp,
                    safeDatasetSource,
                    safeRunLabel,
                    safeExperimentTag,
                    resolvedEngine,
                    safeParameterSnapshot,
                    safeNotes,
                    0,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D,
                    List.of()
            );
            archiveReport(emptyReport, normalizedCases);
            return emptyReport;
        }

        List<QualityEvalCaseResult> results = new ArrayList<>();
        for (QualityEvalCase evalCase : normalizedCases) {
            results.add(evaluateSingleCase(evalCase));
        }

        int total = results.size();
        double avgFaithfulness = results.stream().mapToDouble(QualityEvalCaseResult::faithfulness).average().orElse(0.0D);
        double avgAnswerRelevancy = results.stream().mapToDouble(QualityEvalCaseResult::answerRelevancy).average().orElse(0.0D);
        double avgContextPrecision = results.stream().mapToDouble(QualityEvalCaseResult::contextPrecision).average().orElse(0.0D);
        double avgContextRecall = results.stream().mapToDouble(QualityEvalCaseResult::contextRecall).average().orElse(0.0D);

        QualityEvalReport report = new QualityEvalReport(
                runId,
                reportTimestamp,
                safeDatasetSource,
                safeRunLabel,
                safeExperimentTag,
                resolvedEngine,
                safeParameterSnapshot,
                safeNotes,
                total,
                avgFaithfulness,
                avgAnswerRelevancy,
                avgContextPrecision,
                avgContextRecall,
                results
        );
        archiveReport(report, normalizedCases);
        return report;
    }

    public QualityEvalCaseResult evaluateSingleCase(QualityEvalCase evalCase) {
        QualityEvalCase safeCase = evalCase == null ? new QualityEvalCase("", "", List.of(), "") : evalCase;
        RAGService.KnowledgePacket packet = ragService.buildKnowledgePacket(safeCase.query(), "", false);
        String retrievedContext = packet == null || packet.context() == null ? "" : packet.context();
        String generatedAnswer = generateAnswer(safeCase.query(), retrievedContext);

        CompletableFuture<MetricScore> faithFuture = CompletableFuture.supplyAsync(
                () -> computeFaithfulness(generatedAnswer, retrievedContext),
                ragRetrieveExecutor
        );
        CompletableFuture<MetricScore> answerRelFuture = CompletableFuture.supplyAsync(
                () -> computeAnswerRelevancy(safeCase.query(), generatedAnswer),
                ragRetrieveExecutor
        );
        CompletableFuture<MetricScore> contextPrecisionFuture = CompletableFuture.supplyAsync(
                () -> computeContextPrecision(safeCase.query(), retrievedContext),
                ragRetrieveExecutor
        );
        CompletableFuture<MetricScore> contextRecallFuture = CompletableFuture.supplyAsync(
                () -> computeContextRecall(safeCase.groundTruthAnswer(), retrievedContext),
                ragRetrieveExecutor
        );

        CompletableFuture.allOf(faithFuture, answerRelFuture, contextPrecisionFuture, contextRecallFuture).join();

        MetricScore faithfulness = faithFuture.join();
        MetricScore answerRelevancy = answerRelFuture.join();
        MetricScore contextPrecision = contextPrecisionFuture.join();
        MetricScore contextRecall = contextRecallFuture.join();

        Map<String, String> rationales = new LinkedHashMap<>();
        rationales.put("faithfulness", faithfulness.reason());
        rationales.put("answerRelevancy", answerRelevancy.reason());
        rationales.put("contextPrecision", contextPrecision.reason());
        rationales.put("contextRecall", contextRecall.reason());

        return new QualityEvalCaseResult(
                safeCase.query(),
                EvaluationServiceHelper.normalizeText(safeCase.tag(), "manual"),
                EvaluationServiceHelper.normalizeText(safeCase.groundTruthAnswer(), ""),
                generatedAnswer,
                retrievedContext,
                faithfulness.score(),
                answerRelevancy.score(),
                contextPrecision.score(),
                contextRecall.score(),
                rationales
        );
    }

    public List<QualityEvalRunSummary> listRecentRuns(int limit) {
        ensureEvalEnabled();
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 100);
        Map<String, QualityEvalRunSummary> merged = new LinkedHashMap<>();
        for (QualityEvalRunSummary item : loadRecentRunsFromPersistence(Math.max(safeLimit, 20))) {
            merged.put(item.runId(), item);
        }
        for (QualityEvalReport report : reportHistory) {
            merged.put(report.runId(), toRunSummary(report));
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(QualityEvalRunSummary::timestamp).reversed())
                .limit(safeLimit)
                .toList();
    }

    public QualityEvalReport getRunDetail(String runId) {
        ensureEvalEnabled();
        String safeRunId = EvaluationServiceHelper.normalizeText(runId, "");
        if (safeRunId.isBlank()) {
            return null;
        }
        QualityEvalReport memoryReport = reportDetailHistory.get(safeRunId);
        if (memoryReport != null) {
            return memoryReport;
        }
        return loadRunDetailFromPersistence(safeRunId);
    }

    public QualityEvalComparison compareRuns(String baselineId, String candidateId) {
        ensureEvalEnabled();
        QualityEvalReport baseline = getRunDetail(baselineId);
        QualityEvalReport candidate = getRunDetail(candidateId);
        if (baseline == null || candidate == null) {
            return null;
        }

        Map<String, QualityEvalCaseResult> baselineByQuery = baseline.results().stream()
                .collect(Collectors.toMap(QualityEvalCaseResult::query, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<String, QualityEvalCaseResult> candidateByQuery = candidate.results().stream()
                .collect(Collectors.toMap(QualityEvalCaseResult::query, item -> item, (left, right) -> left, LinkedHashMap::new));

        List<QualityEvalCaseComparison> changedCases = new ArrayList<>();
        for (Map.Entry<String, QualityEvalCaseResult> entry : candidateByQuery.entrySet()) {
            QualityEvalCaseResult candidateResult = entry.getValue();
            QualityEvalCaseResult baselineResult = baselineByQuery.get(entry.getKey());
            if (baselineResult == null) {
                continue;
            }
            if (hasMetricDiff(baselineResult, candidateResult)) {
                changedCases.add(new QualityEvalCaseComparison(
                        entry.getKey(),
                        baselineResult.faithfulness(),
                        candidateResult.faithfulness(),
                        baselineResult.answerRelevancy(),
                        candidateResult.answerRelevancy(),
                        baselineResult.contextPrecision(),
                        candidateResult.contextPrecision(),
                        baselineResult.contextRecall(),
                        candidateResult.contextRecall()
                ));
            }
        }

        return new QualityEvalComparison(
                baseline.runId(),
                candidate.runId(),
                new EvalMetricDelta(baseline.avgFaithfulness(), candidate.avgFaithfulness(), candidate.avgFaithfulness() - baseline.avgFaithfulness()),
                new EvalMetricDelta(baseline.avgAnswerRelevancy(), candidate.avgAnswerRelevancy(), candidate.avgAnswerRelevancy() - baseline.avgAnswerRelevancy()),
                new EvalMetricDelta(baseline.avgContextPrecision(), candidate.avgContextPrecision(), candidate.avgContextPrecision() - baseline.avgContextPrecision()),
                new EvalMetricDelta(baseline.avgContextRecall(), candidate.avgContextRecall(), candidate.avgContextRecall() - baseline.avgContextRecall()),
                changedCases
        );
    }

    public QualityEvalTrend getTrend(int limit) {
        ensureEvalEnabled();
        List<QualityEvalRunSummary> runs = listRecentRuns(limit);
        if (runs.isEmpty()) {
            return new QualityEvalTrend(List.of(), 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        return new QualityEvalTrend(
                runs,
                runs.stream().mapToDouble(QualityEvalRunSummary::avgFaithfulness).average().orElse(0.0D),
                runs.stream().mapToDouble(QualityEvalRunSummary::avgAnswerRelevancy).average().orElse(0.0D),
                runs.stream().mapToDouble(QualityEvalRunSummary::avgContextPrecision).average().orElse(0.0D),
                runs.stream().mapToDouble(QualityEvalRunSummary::avgContextRecall).average().orElse(0.0D),
                runs.stream().mapToDouble(QualityEvalRunSummary::avgFaithfulness).max().orElse(0.0D)
        );
    }

    public boolean isEvalEnabled() {
        return observabilitySwitchProperties.isRagQualityEvalEnabled();
    }

    /**
     * 解析评测引擎，优先使用传入值，其次使用配置值。
     */
    public String resolveEngine(String requestedEngine) {
        if (requestedEngine != null && !requestedEngine.isBlank()) {
            return requestedEngine.trim();
        }
        return evaluationEngine == null || evaluationEngine.isBlank() ? "java" : evaluationEngine;
    }

    /**
     * 获取引擎可用状态。
     */
    public Map<String, Object> getEngineStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("currentEngine", resolveEngine(null));
        status.put("javaEngineAvailable", true);
        status.put("ragasEngineAvailable", false);
        return status;
    }

    private String generateAnswer(String query, String context) {
        String systemPrompt = "你是一位面试辅导助手。根据提供的参考资料回答面试问题。如果参考资料不足，请基于你的知识回答。";
        String userPrompt = "参考资料：\n" + (context == null ? "" : context) + "\n\n问题：" + (query == null ? "" : query);
        try {
            return EvaluationServiceHelper.normalizeText(routingChatService.call(systemPrompt, userPrompt, ModelRouteType.GENERAL, "rag-quality-eval-generate"), "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private MetricScore computeFaithfulness(String generatedAnswer, String retrievedContext) {
        String systemPrompt = "你是RAG评测专家。请基于检索上下文评估答案忠实度。输出严格JSON，不要输出其他文本。";
        String userPrompt = "请将生成答案拆分为原子声明，并判断每条声明是否被检索上下文支持。\n"
                + "返回格式：{\"claims\":[{\"claim\":\"...\",\"supported\":true,\"reason\":\"...\"}]}\n"
                + "生成答案：\n" + EvaluationServiceHelper.normalizeText(generatedAnswer, "") + "\n\n"
                + "检索上下文：\n" + EvaluationServiceHelper.normalizeText(retrievedContext, "");
        JsonNode root = parseEvaluationJson(systemPrompt, userPrompt);
        if (root == null || !root.has("claims") || !root.get("claims").isArray()) {
            return new MetricScore(0.0D, "faithfulness 解析失败，按0分处理");
        }

        int total = 0;
        int supported = 0;
        for (JsonNode item : root.get("claims")) {
            total++;
            if (item.path("supported").asBoolean(false)) {
                supported++;
            }
        }
        if (total <= 0) {
            return new MetricScore(0.0D, "未拆分出有效声明");
        }
        double score = EvaluationServiceHelper.clamp01((double) supported / total);
        return new MetricScore(score, "supportedClaims=" + supported + "/" + total);
    }

    private MetricScore computeAnswerRelevancy(String query, String generatedAnswer) {
        String systemPrompt = "你是RAG评测专家。请评估回答与问题的相关性、完整性和聚焦度。输出严格JSON。";
        String userPrompt = "请给出0到1分，并简要说明原因。\n"
                + "返回格式：{\"score\":0.85,\"reason\":\"...\"}\n"
                + "问题：" + EvaluationServiceHelper.normalizeText(query, "") + "\n"
                + "回答：" + EvaluationServiceHelper.normalizeText(generatedAnswer, "");
        JsonNode root = parseEvaluationJson(systemPrompt, userPrompt);
        if (root == null) {
            return new MetricScore(0.0D, "answerRelevancy 解析失败，按0分处理");
        }
        double score = EvaluationServiceHelper.clamp01(root.path("score").asDouble(0.0D));
        String reason = EvaluationServiceHelper.normalizeText(root.path("reason").asText(""), "模型未提供原因");
        return new MetricScore(score, reason);
    }

    private MetricScore computeContextPrecision(String query, String retrievedContext) {
        List<String> chunks = splitContextChunks(retrievedContext);
        if (chunks.isEmpty()) {
            return new MetricScore(0.0D, "无可评估上下文分块");
        }

        String chunkText = buildIndexedChunks(chunks);
        String systemPrompt = "你是RAG评测专家。请判断每个检索分块是否与问题相关。输出严格JSON。";
        String userPrompt = "请对每个chunk判断relevant true/false，并说明原因。\n"
                + "返回格式：{\"chunks\":[{\"index\":0,\"relevant\":true,\"reason\":\"...\"}]}\n"
                + "问题：" + EvaluationServiceHelper.normalizeText(query, "") + "\n"
                + "分块：\n" + chunkText;

        JsonNode root = parseEvaluationJson(systemPrompt, userPrompt);
        if (root == null || !root.has("chunks") || !root.get("chunks").isArray()) {
            return new MetricScore(0.0D, "contextPrecision 解析失败，按0分处理");
        }

        Map<Integer, Boolean> relevantMap = new LinkedHashMap<>();
        for (JsonNode node : root.get("chunks")) {
            int index = node.path("index").asInt(-1);
            if (index >= 0 && index < chunks.size()) {
                relevantMap.put(index, node.path("relevant").asBoolean(false));
            }
        }

        int totalRelevant = 0;
        int relevantPrefixCount = 0;
        double precisionSum = 0.0D;
        for (int i = 0; i < chunks.size(); i++) {
            boolean relevant = relevantMap.getOrDefault(i, false);
            if (!relevant) {
                continue;
            }
            totalRelevant++;
            relevantPrefixCount++;
            precisionSum += (double) relevantPrefixCount / (i + 1);
        }
        if (totalRelevant == 0) {
            return new MetricScore(0.0D, "相关分块数为0");
        }
        double score = EvaluationServiceHelper.clamp01(precisionSum / totalRelevant);
        return new MetricScore(score, "relevantChunks=" + totalRelevant + "/" + chunks.size());
    }

    private MetricScore computeContextRecall(String groundTruthAnswer, String retrievedContext) {
        String systemPrompt = "你是RAG评测专家。请判断检索上下文是否覆盖标准答案要点。输出严格JSON。";
        String userPrompt = "请将标准答案拆分为要点，并判断每个要点是否被上下文覆盖。\n"
                + "返回格式：{\"statements\":[{\"statement\":\"...\",\"supported\":true}]}\n"
                + "标准答案：\n" + EvaluationServiceHelper.normalizeText(groundTruthAnswer, "") + "\n\n"
                + "检索上下文：\n" + EvaluationServiceHelper.normalizeText(retrievedContext, "");
        JsonNode root = parseEvaluationJson(systemPrompt, userPrompt);
        if (root == null || !root.has("statements") || !root.get("statements").isArray()) {
            return new MetricScore(0.0D, "contextRecall 解析失败，按0分处理");
        }

        int total = 0;
        int supported = 0;
        for (JsonNode item : root.get("statements")) {
            total++;
            if (item.path("supported").asBoolean(false)) {
                supported++;
            }
        }
        if (total <= 0) {
            return new MetricScore(0.0D, "未拆分出有效要点");
        }
        double score = EvaluationServiceHelper.clamp01((double) supported / total);
        return new MetricScore(score, "coveredStatements=" + supported + "/" + total);
    }

    private JsonNode parseEvaluationJson(String systemPrompt, String userPrompt) {
        try {
            String raw = routingChatService.call(systemPrompt, userPrompt, ModelRouteType.GENERAL, "rag-quality-eval");
            JsonResult<JsonNode> result = llmJsonParser.parseTree(raw, null, null);
            return result.success() ? result.data() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stripMarkdownCodeBlock(String raw) {
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

    private List<String> splitContextChunks(String retrievedContext) {
        String source = EvaluationServiceHelper.normalizeText(retrievedContext, "");
        if (source.isBlank()) {
            return List.of();
        }
        String[] parts = source.split("\\n\\s*---+\\s*\\n|\\n\\n+");
        List<String> chunks = new ArrayList<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String chunk = part.trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    private String buildIndexedChunks(List<String> chunks) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            builder.append("[").append(i).append("] ").append(chunks.get(i));
            if (i < chunks.size() - 1) {
                builder.append("\n\n");
            }
        }
        return builder.toString();
    }

    private List<QualityEvalCase> normalizeCases(List<QualityEvalCase> cases) {
        if (cases == null || cases.isEmpty()) {
            return List.of();
        }
        return cases.stream()
                .filter(item -> item != null && item.query() != null && !item.query().isBlank())
                .map(item -> {
                    List<String> concepts = item.groundTruthKeyConcepts() == null ? List.of() : item.groundTruthKeyConcepts().stream()
                            .map(String::trim)
                            .filter(word -> !word.isBlank())
                            .distinct()
                            .toList();
                    return new QualityEvalCase(
                            item.query().trim(),
                            EvaluationServiceHelper.normalizeText(item.groundTruthAnswer(), ""),
                            concepts,
                            EvaluationServiceHelper.normalizeText(item.tag(), "manual")
                    );
                })
                .toList();
    }

    private void archiveReport(QualityEvalReport report, List<QualityEvalCase> cases) {
        if (report == null) {
            return;
        }
        reportHistory.removeIf(item -> Objects.equals(item.runId(), report.runId()));
        reportHistory.addFirst(report);
        reportDetailHistory.put(report.runId(), report);
        while (reportHistory.size() > MAX_REPORT_HISTORY) {
            QualityEvalReport removed = reportHistory.removeLast();
            reportDetailHistory.remove(removed.runId());
        }
        persistReport(report, cases);
    }

    private void persistReport(QualityEvalReport report, List<QualityEvalCase> cases) {
        if (!persistenceAvailable() || report == null) {
            return;
        }
        try {
            RagQualityEvalRunDO existingRun = ragQualityEvalRunMapper.selectOne(new LambdaQueryWrapper<RagQualityEvalRunDO>()
                    .eq(RagQualityEvalRunDO::getRunId, report.runId())
                    .last("LIMIT 1"));
            RagQualityEvalRunDO runDO = toRunDO(report);
            if (existingRun == null) {
                ragQualityEvalRunMapper.insert(runDO);
            } else {
                runDO.setId(existingRun.getId());
                ragQualityEvalRunMapper.updateById(runDO);
            }

            ragQualityEvalCaseMapper.delete(new LambdaQueryWrapper<RagQualityEvalCaseDO>()
                    .eq(RagQualityEvalCaseDO::getRunId, report.runId()));
            for (int i = 0; i < report.results().size(); i++) {
                QualityEvalCaseResult result = report.results().get(i);
                QualityEvalCase evalCase = i < cases.size() ? cases.get(i) : new QualityEvalCase(result.query(), result.groundTruthAnswer(), List.of(), result.tag());
                ragQualityEvalCaseMapper.insert(toCaseDO(report.runId(), i, evalCase, result));
            }
        } catch (Exception ignored) {
            // 评测持久化失败时保留内存历史，不中断主接口返回。
        }
    }

    private List<QualityEvalRunSummary> loadRecentRunsFromPersistence(int limit) {
        if (!persistenceAvailable()) {
            return List.of();
        }
        try {
            return ragQualityEvalRunMapper.selectList(new LambdaQueryWrapper<RagQualityEvalRunDO>()
                            .orderByDesc(RagQualityEvalRunDO::getCreatedAt, RagQualityEvalRunDO::getId)
                            .last("LIMIT " + Math.max(1, Math.min(limit, MAX_REPORT_HISTORY))))
                    .stream()
                    .map(this::toRunSummary)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private QualityEvalReport loadRunDetailFromPersistence(String runId) {
        if (!persistenceAvailable()) {
            return null;
        }
        try {
            RagQualityEvalRunDO runDO = ragQualityEvalRunMapper.selectOne(new LambdaQueryWrapper<RagQualityEvalRunDO>()
                    .eq(RagQualityEvalRunDO::getRunId, runId)
                    .last("LIMIT 1"));
            if (runDO == null) {
                return null;
            }
            List<RagQualityEvalCaseDO> caseRows = ragQualityEvalCaseMapper.selectList(new LambdaQueryWrapper<RagQualityEvalCaseDO>()
                    .eq(RagQualityEvalCaseDO::getRunId, runId)
                    .orderByAsc(RagQualityEvalCaseDO::getCaseIndex, RagQualityEvalCaseDO::getId));
            return toReport(runDO, caseRows);
        } catch (Exception ignored) {
            return null;
        }
    }

    private RagQualityEvalRunDO toRunDO(QualityEvalReport report) {
        RagQualityEvalRunDO runDO = new RagQualityEvalRunDO();
        runDO.setRunId(report.runId());
        runDO.setDatasetSource(report.datasetSource());
        runDO.setRunLabel(report.runLabel());
        runDO.setExperimentTag(report.experimentTag());
        runDO.setParameterSnapshot(report.parameterSnapshot());
        runDO.setNotes(report.notes());
        runDO.setTotalCases(report.totalCases());
        runDO.setAvgFaithfulness(report.avgFaithfulness());
        runDO.setAvgAnswerRelevancy(report.avgAnswerRelevancy());
        runDO.setAvgContextPrecision(report.avgContextPrecision());
        runDO.setAvgContextRecall(report.avgContextRecall());
        runDO.setReportTimestamp(report.timestamp());
        return runDO;
    }

    private RagQualityEvalCaseDO toCaseDO(String runId, int caseIndex, QualityEvalCase evalCase, QualityEvalCaseResult result) {
        RagQualityEvalCaseDO caseDO = new RagQualityEvalCaseDO();
        caseDO.setRunId(runId);
        caseDO.setCaseIndex(caseIndex);
        caseDO.setQuery(result.query());
        caseDO.setGroundTruthAnswer(result.groundTruthAnswer());
        caseDO.setGeneratedAnswer(result.generatedAnswer());
        caseDO.setRetrievedContext(result.retrievedContext());
        caseDO.setTag(EvaluationServiceHelper.normalizeText(result.tag(), EvaluationServiceHelper.normalizeText(evalCase.tag(), "")));
        caseDO.setFaithfulness(result.faithfulness());
        caseDO.setAnswerRelevancy(result.answerRelevancy());
        caseDO.setContextPrecision(result.contextPrecision());
        caseDO.setContextRecall(result.contextRecall());
        caseDO.setMetricRationales(result.rationales() == null ? Map.of() : result.rationales());
        return caseDO;
    }

    private QualityEvalRunSummary toRunSummary(RagQualityEvalRunDO runDO) {
        Map<String, Object> snapshot = EvaluationServiceHelper.safeMap(runDO.getParameterSnapshot());
        String engine = snapshot.containsKey("engine") ? String.valueOf(snapshot.get("engine")) : "java";
        return new QualityEvalRunSummary(
                runDO.getRunId(),
                runDO.getReportTimestamp(),
                runDO.getDatasetSource(),
                runDO.getRunLabel(),
                runDO.getExperimentTag(),
                engine,
                EvaluationServiceHelper.safeInt(runDO.getTotalCases()),
                EvaluationServiceHelper.safeDouble(runDO.getAvgFaithfulness()),
                EvaluationServiceHelper.safeDouble(runDO.getAvgAnswerRelevancy()),
                EvaluationServiceHelper.safeDouble(runDO.getAvgContextPrecision()),
                EvaluationServiceHelper.safeDouble(runDO.getAvgContextRecall())
        );
    }

    private QualityEvalRunSummary toRunSummary(QualityEvalReport report) {
        return new QualityEvalRunSummary(
                report.runId(),
                report.timestamp(),
                report.datasetSource(),
                report.runLabel(),
                report.experimentTag(),
                report.engine(),
                report.totalCases(),
                report.avgFaithfulness(),
                report.avgAnswerRelevancy(),
                report.avgContextPrecision(),
                report.avgContextRecall()
        );
    }

    private QualityEvalReport toReport(RagQualityEvalRunDO runDO, List<RagQualityEvalCaseDO> caseRows) {
        List<QualityEvalCaseResult> results = caseRows == null ? List.of() : caseRows.stream()
                .map(item -> new QualityEvalCaseResult(
                        item.getQuery(),
                        item.getTag() == null ? "" : item.getTag(),
                        item.getGroundTruthAnswer() == null ? "" : item.getGroundTruthAnswer(),
                        item.getGeneratedAnswer() == null ? "" : item.getGeneratedAnswer(),
                        item.getRetrievedContext() == null ? "" : item.getRetrievedContext(),
                        EvaluationServiceHelper.safeDouble(item.getFaithfulness()),
                        EvaluationServiceHelper.safeDouble(item.getAnswerRelevancy()),
                        EvaluationServiceHelper.safeDouble(item.getContextPrecision()),
                        EvaluationServiceHelper.safeDouble(item.getContextRecall()),
                        item.getMetricRationales() == null ? Map.of() : item.getMetricRationales()
                ))
                .toList();

        Map<String, Object> snapshot = EvaluationServiceHelper.safeMap(runDO.getParameterSnapshot());
        String engine = snapshot.containsKey("engine") ? String.valueOf(snapshot.get("engine")) : "java";

        return new QualityEvalReport(
                runDO.getRunId(),
                runDO.getReportTimestamp(),
                runDO.getDatasetSource(),
                runDO.getRunLabel(),
                runDO.getExperimentTag(),
                engine,
                snapshot,
                EvaluationServiceHelper.normalizeText(runDO.getNotes(), ""),
                EvaluationServiceHelper.safeInt(runDO.getTotalCases()),
                EvaluationServiceHelper.safeDouble(runDO.getAvgFaithfulness()),
                EvaluationServiceHelper.safeDouble(runDO.getAvgAnswerRelevancy()),
                EvaluationServiceHelper.safeDouble(runDO.getAvgContextPrecision()),
                EvaluationServiceHelper.safeDouble(runDO.getAvgContextRecall()),
                results
        );
    }

    private boolean hasMetricDiff(QualityEvalCaseResult baseline, QualityEvalCaseResult candidate) {
        double eps = 0.000001D;
        return Math.abs(candidate.faithfulness() - baseline.faithfulness()) > eps
                || Math.abs(candidate.answerRelevancy() - baseline.answerRelevancy()) > eps
                || Math.abs(candidate.contextPrecision() - baseline.contextPrecision()) > eps
                || Math.abs(candidate.contextRecall() - baseline.contextRecall()) > eps;
    }

    private void ensureEvalEnabled() {
        if (!observabilitySwitchProperties.isRagQualityEvalEnabled()) {
            throw new IllegalStateException("RAG 生成质量评测已关闭，请设置 app.observability.rag-quality-eval-enabled=true 后重试。");
        }
    }

    private boolean persistenceAvailable() {
        return ragQualityEvalRunMapper != null && ragQualityEvalCaseMapper != null;
    }

    private EvalRunOptions buildDatasetRunOptions(String datasetFile, String experimentTag, String notes) {
        String normalizedDatasetFile = EvaluationServiceHelper.normalizeDatasetFilename(datasetFile, DEFAULT_DATASET_FILE, "rag_quality_ground_truth");
        String datasetSource = EvaluationServiceHelper.stripJsonSuffix(normalizedDatasetFile);
        return new EvalRunOptions(
                normalizedDatasetFile,
                datasetSource,
                experimentTag,
                Map.of("dataset", datasetSource, "datasetFile", normalizedDatasetFile),
                notes
        );
    }

    private String resolveDatasetFilename(String dataset) {
        return EvaluationServiceHelper.resolveDatasetFilename(dataset, DEFAULT_DATASET_FILE, DATASET_FILE_MAPPING, "rag_quality_ground_truth");
    }

    private record MetricScore(double score, String reason) {
    }

    public record QualityEvalCase(String query, String groundTruthAnswer, List<String> groundTruthKeyConcepts, String tag) {
    }

    public record QualityEvalCaseResult(
            String query,
            String tag,
            String groundTruthAnswer,
            String generatedAnswer,
            String retrievedContext,
            double faithfulness,
            double answerRelevancy,
            double contextPrecision,
            double contextRecall,
            Map<String, String> rationales
    ) {
    }

    public record QualityEvalReport(
            String runId,
            String timestamp,
            String datasetSource,
            String runLabel,
            String experimentTag,
            String engine,
            Map<String, Object> parameterSnapshot,
            String notes,
            int totalCases,
            double avgFaithfulness,
            double avgAnswerRelevancy,
            double avgContextPrecision,
            double avgContextRecall,
            List<QualityEvalCaseResult> results
    ) {
    }

    public record QualityEvalRunSummary(
            String runId,
            String timestamp,
            String datasetSource,
            String runLabel,
            String experimentTag,
            String engine,
            int totalCases,
            double avgFaithfulness,
            double avgAnswerRelevancy,
            double avgContextPrecision,
            double avgContextRecall
    ) {
    }

    public record EvalRunOptions(
            String datasetSource,
            String runLabel,
            String experimentTag,
            Map<String, Object> parameterSnapshot,
            String notes
    ) {
    }

    public record QualityEvalComparison(
            String baselineRunId,
            String candidateRunId,
            EvalMetricDelta faithfulness,
            EvalMetricDelta answerRelevancy,
            EvalMetricDelta contextPrecision,
            EvalMetricDelta contextRecall,
            List<QualityEvalCaseComparison> changedCases
    ) {
    }

    public record EvalMetricDelta(double baseline, double candidate, double delta) {
    }

    public record QualityEvalCaseComparison(
            String query,
            double baselineFaithfulness,
            double candidateFaithfulness,
            double baselineAnswerRelevancy,
            double candidateAnswerRelevancy,
            double baselineContextPrecision,
            double candidateContextPrecision,
            double baselineContextRecall,
            double candidateContextRecall
    ) {
    }

    public record QualityEvalTrend(
            List<QualityEvalRunSummary> runs,
            double avgFaithfulness,
            double avgAnswerRelevancy,
            double avgContextPrecision,
            double avgContextRecall,
            double bestFaithfulness
    ) {
    }

    public record EvalDatasetDefinition(
            String datasetId,
            String fileName,
            String title,
            String description
    ) {
    }
}








