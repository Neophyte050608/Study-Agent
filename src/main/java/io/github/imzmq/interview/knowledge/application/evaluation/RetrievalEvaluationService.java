package io.github.imzmq.interview.knowledge.application.evaluation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.config.observability.ObservabilitySwitchProperties;
import io.github.imzmq.interview.entity.knowledge.RetrievalEvalCaseDO;
import io.github.imzmq.interview.entity.knowledge.RetrievalEvalRunDO;
import io.github.imzmq.interview.knowledge.application.RAGService;
import io.github.imzmq.interview.mapper.knowledge.RetrievalEvalCaseMapper;
import io.github.imzmq.interview.mapper.knowledge.RetrievalEvalRunMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * 检索效果评测服务。
 *
 * <p>职责说明：</p>
 * <p>1. 加载默认评测集或用户自定义评测集，对当前检索链路执行离线评测。</p>
 * <p>2. 直接复用 `RAGService` 的主检索链路，确保评测口径与线上召回逻辑一致。</p>
 * <p>3. 自动持久化评测结果，提供历史查询、详情回放与 A/B 对比能力。</p>
 */
@Service
public class RetrievalEvaluationService {

    private static final int MAX_REPORT_HISTORY = 100;
    private static final String DEFAULT_DATASET_FILE = "rag_ground_truth.json";
    private static final Map<String, String> DATASET_FILE_MAPPING = Map.of(
            "default", DEFAULT_DATASET_FILE,
            "baseline", "rag_ground_truth_baseline.json",
            "advanced", "rag_ground_truth_advanced.json",
            "project", "rag_ground_truth_project.json"
    );

    private final RAGService ragService;
    private final ObservabilitySwitchProperties observabilitySwitchProperties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final RetrievalEvalRunMapper retrievalEvalRunMapper;
    private final RetrievalEvalCaseMapper retrievalEvalCaseMapper;

    /**
     * 内存历史兜底：当数据库不可用时，仍然保留最近评测结果供列表与对比使用。
     */
    private final Deque<RetrievalEvalReport> reportHistory = new ConcurrentLinkedDeque<>();

    /**
     * 内存详情兜底：按 runId 缓存完整报告，避免数据库不可用时详情接口失效。
     */
    private final Map<String, RetrievalEvalReport> reportDetailHistory = new ConcurrentHashMap<>();

    /**
     * Spring 正常注入使用的构造器。
     *
     * @param ragService RAG 主检索服务
     * @param observabilitySwitchProperties 评测开关配置
     * @param resourceLoader 资源加载器
     * @param objectMapper JSON 映射器
     * @param retrievalEvalRunMapper 评测运行汇总 Mapper
     * @param retrievalEvalCaseMapper 评测样本明细 Mapper
     */
    @Autowired
    public RetrievalEvaluationService(
            RAGService ragService,
            ObservabilitySwitchProperties observabilitySwitchProperties,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            @Nullable RetrievalEvalRunMapper retrievalEvalRunMapper,
            @Nullable RetrievalEvalCaseMapper retrievalEvalCaseMapper
    ) {
        this.ragService = ragService;
        this.observabilitySwitchProperties = observabilitySwitchProperties;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.retrievalEvalRunMapper = retrievalEvalRunMapper;
        this.retrievalEvalCaseMapper = retrievalEvalCaseMapper;
    }

    /**
     * 单元测试与轻量场景使用的构造器。
     */
    public RetrievalEvaluationService(
            RAGService ragService,
            ObservabilitySwitchProperties observabilitySwitchProperties,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper
    ) {
        this(ragService, observabilitySwitchProperties, resourceLoader, objectMapper, null, null);
    }

    /**
     * 运行默认评测集。
     *
     * @return 默认评测报告
     */
    public RetrievalEvalReport runDefaultEval() {
        return runEvalByDataset(null);
    }

    /**
     * 运行指定数据集的检索评测。
     *
     * @param dataset 数据集别名或文件名
     * @return 评测报告
     */
    public RetrievalEvalReport runEvalByDataset(String dataset) {
        ensureEvalEnabled();
        String datasetFile = resolveDatasetFilename(dataset);
        try {
            InputStream inputStream = resourceLoader.getResource("classpath:eval/" + datasetFile).getInputStream();
            List<EvalCase> cases = objectMapper.readValue(inputStream, new TypeReference<List<EvalCase>>() {});
            return runCustomEval(cases, buildDatasetRunOptions(datasetFile, "default-benchmark", "默认基准评测集"));
        } catch (Exception ignored) {
            // 当默认评测文件不可用时，保留一组最小兜底用例，避免评测入口完全不可用。
            List<EvalCase> cases = List.of(
                    new EvalCase("Spring事务传播行为有哪些", List.of("传播", "事务"), "default"),
                    new EvalCase("Redis为什么快", List.of("redis", "内存"), "default"),
                    new EvalCase("JVM垃圾收集器对比", List.of("gc", "垃圾回收"), "default")
            );
            return runCustomEval(cases, new EvalRunOptions(datasetFile, datasetFile + "-fallback", "default-benchmark", Map.of(), "评测集缺失时的兜底样本"));
        }
    }

    /**
     * 返回内置可选数据集列表。
     */
    public List<EvalDatasetDefinition> listBuiltInDatasets() {
        ensureEvalEnabled();
        return List.of(
                new EvalDatasetDefinition("default", DEFAULT_DATASET_FILE, "默认全集", "完整检索黄金集"),
                new EvalDatasetDefinition("baseline", "rag_ground_truth_baseline.json", "基础档", "基础知识召回评测"),
                new EvalDatasetDefinition("advanced", "rag_ground_truth_advanced.json", "进阶档", "RAG/Agent/架构能力评测"),
                new EvalDatasetDefinition("project", "rag_ground_truth_project.json", "项目档", "项目实战知识召回评测")
        );
    }

    /**
     * 对给定评测集执行离线评测，默认归类为手工运行。
     *
     * @param cases 评测用例列表
     * @return 评测结果
     */
    public RetrievalEvalReport runCustomEval(List<EvalCase> cases) {
        return runCustomEval(cases, new EvalRunOptions("manual", "manual-eval", "", Map.of(), ""));
    }

    /**
     * 对给定评测集执行离线评测，并自动留档结果。
     *
     * @param cases 评测用例列表
     * @param datasetSource 数据集来源
     * @param runLabel 运行标签
     * @return 评测结果
     */
    public RetrievalEvalReport runCustomEval(List<EvalCase> cases, String datasetSource, String runLabel) {
        return runCustomEval(cases, new EvalRunOptions(datasetSource, runLabel, "", Map.of(), ""));
    }

    /**
     * 对给定评测集执行离线评测，并自动留档结果与实验元数据。
     *
     * @param cases 评测用例列表
     * @param options 评测运行配置
     * @return 评测结果
     */
    public RetrievalEvalReport runCustomEval(List<EvalCase> cases, EvalRunOptions options) {
        ensureEvalEnabled();
        List<EvalCase> normalizedCases = normalizeCases(cases);
        String runId = UUID.randomUUID().toString();
        String reportTimestamp = Instant.now().toString();
        EvalRunOptions safeOptions = options == null ? new EvalRunOptions("manual", "manual-eval", "", Map.of(), "") : options;
        String safeDatasetSource = normalizeText(safeOptions.datasetSource(), "manual");
        String safeRunLabel = normalizeText(safeOptions.runLabel(), safeDatasetSource + "-eval");
        String safeExperimentTag = normalizeText(safeOptions.experimentTag(), "");
        Map<String, Object> safeParameterSnapshot = safeOptions.parameterSnapshot() == null ? Map.of() : new LinkedHashMap<>(safeOptions.parameterSnapshot());
        String safeNotes = normalizeText(safeOptions.notes(), "");
        if (normalizedCases.isEmpty()) {
            RetrievalEvalReport emptyReport = new RetrievalEvalReport(
                    runId,
                    reportTimestamp,
                    safeDatasetSource,
                    safeRunLabel,
                    safeExperimentTag,
                    safeParameterSnapshot,
                    safeNotes,
                    0,
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

        List<EvalResult> results = new ArrayList<>();
        int hitAt1 = 0;
        int hitAt3 = 0;
        int hitAt5 = 0;
        double sumRR = 0.0D;

        for (EvalCase item : normalizedCases) {
            EvalResult result = evaluateSingle(item);
            results.add(result);

            if (result.rank() == 1) {
                hitAt1++;
            }
            if (result.rank() > 0 && result.rank() <= 3) {
                hitAt3++;
            }
            if (result.rank() > 0 && result.rank() <= 5) {
                hitAt5++;
            }
            if (result.rank() > 0) {
                sumRR += 1.0D / result.rank();
            }
        }

        int total = normalizedCases.size();
        double recallAt1 = (double) hitAt1 / total;
        double recallAt3 = (double) hitAt3 / total;
        double recallAt5 = (double) hitAt5 / total;
        double mrr = sumRR / total;

        RetrievalEvalReport report = new RetrievalEvalReport(
                runId,
                reportTimestamp,
                safeDatasetSource,
                safeRunLabel,
                safeExperimentTag,
                safeParameterSnapshot,
                safeNotes,
                total,
                hitAt5,
                recallAt1,
                recallAt3,
                recallAt5,
                mrr,
                results
        );
        archiveReport(report, normalizedCases);
        return report;
    }

    /**
     * 对单条评测用例执行检索评估。
     *
     * @param evalCase 单条评测用例
     * @return 单条评测结果
     */
    private EvalResult evaluateSingle(EvalCase evalCase) {
        // 评测阶段显式关闭 Web fallback，确保指标衡量的是本地知识库检索能力。
        RAGService.KnowledgePacket packet = ragService.buildKnowledgePacket(evalCase.query(), "", false);
        List<String> snippets = packet.retrievedDocs().stream()
                .map(Document::getText)
                .limit(5)
                .collect(Collectors.toList());

        int firstHitRank = -1;
        for (int i = 0; i < snippets.size(); i++) {
            if (containsAnyKeyword(snippets.get(i), evalCase.expectedKeywords())) {
                firstHitRank = i + 1;
                break;
            }
        }

        return new EvalResult(evalCase.query(), evalCase.tag(), evalCase.expectedKeywords(), firstHitRank > 0, firstHitRank, snippets);
    }

    /**
     * 判断召回文本是否命中任一关键词。
     *
     * @param text 召回文本
     * @param keywords 预期关键词
     * @return 是否命中
     */
    private boolean containsAnyKeyword(String text, List<String> keywords) {
        if (text == null || text.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return keywords.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> item.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
    }

    /**
     * 规范化评测用例，清理空值与重复关键词。
     *
     * @param cases 原始评测用例
     * @return 规范化后的评测用例
     */
    private List<EvalCase> normalizeCases(List<EvalCase> cases) {
        if (cases == null || cases.isEmpty()) {
            return List.of();
        }
        return cases.stream()
                .filter(item -> item != null && item.query() != null && !item.query().isBlank())
                .map(item -> {
                    List<String> keywords = item.expectedKeywords() == null ? List.of() : item.expectedKeywords().stream()
                            .map(String::trim)
                            .filter(word -> !word.isBlank())
                            .distinct()
                            .collect(Collectors.toList());
                    return new EvalCase(item.query().trim(), keywords, item.tag());
                })
                .collect(Collectors.toList());
    }

    /**
     * 从 CSV 文本解析评测集。
     *
     * @param csvText CSV 文本
     * @return 结构化评测用例
     */
    public List<EvalCase> parseCasesFromCsv(String csvText) {
        ensureEvalEnabled();
        if (csvText == null || csvText.isBlank()) {
            return List.of();
        }
        String[] lines = csvText.split("\\R");
        List<EvalCase> cases = new ArrayList<>();
        int start = 0;
        if (lines.length > 0 && lines[0].toLowerCase(Locale.ROOT).contains("query")) {
            start = 1;
        }
        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.split(",", 2);
            if (parts.length == 0) {
                continue;
            }
            String query = parts[0].trim();
            if (query.isBlank()) {
                continue;
            }
            List<String> keywords = List.of();
            if (parts.length > 1) {
                keywords = List.of(parts[1].split("[|，；]")).stream()
                        .map(String::trim)
                        .filter(item -> !item.isBlank())
                        .collect(Collectors.toList());
            }
            cases.add(new EvalCase(query, keywords, "csv"));
        }
        return normalizeCases(cases);
    }

    /**
     * 查询最近的评测运行列表。
     *
     * @param limit 返回条数上限
     * @return 评测运行摘要列表
     */
    public List<RetrievalEvalRunSummary> listRecentRuns(int limit) {
        ensureEvalEnabled();
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 100);
        Map<String, RetrievalEvalRunSummary> merged = new LinkedHashMap<>();
        for (RetrievalEvalRunSummary item : loadRecentRunsFromPersistence(Math.max(safeLimit, 20))) {
            merged.put(item.runId(), item);
        }
        for (RetrievalEvalReport report : reportHistory) {
            merged.put(report.runId(), toRunSummary(report));
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(RetrievalEvalRunSummary::timestamp).reversed())
                .limit(safeLimit)
                .toList();
    }

    /**
     * 查询单次评测运行详情。
     *
     * @param runId 运行 ID
     * @return 详情；不存在时返回 null
     */
    public RetrievalEvalReport getRunDetail(String runId) {
        ensureEvalEnabled();
        String safeRunId = normalizeText(runId, "");
        if (safeRunId.isBlank()) {
            return null;
        }
        RetrievalEvalReport memoryReport = reportDetailHistory.get(safeRunId);
        if (memoryReport != null) {
            return memoryReport;
        }
        return loadRunDetailFromPersistence(safeRunId);
    }

    /**
     * 对比两次评测运行的指标与样本差异。
     *
     * @param baselineRunId 基线运行 ID
     * @param candidateRunId 候选运行 ID
     * @return 对比结果；任一运行不存在时返回 null
     */
    public RetrievalEvalComparison compareRuns(String baselineRunId, String candidateRunId) {
        ensureEvalEnabled();
        RetrievalEvalReport baseline = getRunDetail(baselineRunId);
        RetrievalEvalReport candidate = getRunDetail(candidateRunId);
        if (baseline == null || candidate == null) {
            return null;
        }

        Map<String, EvalResult> baselineByQuery = baseline.results().stream()
                .collect(Collectors.toMap(EvalResult::query, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<String, EvalResult> candidateByQuery = candidate.results().stream()
                .collect(Collectors.toMap(EvalResult::query, item -> item, (left, right) -> left, LinkedHashMap::new));

        List<EvalCaseComparison> changedCases = new ArrayList<>();
        int improvedCount = 0;
        int regressedCount = 0;

        for (Map.Entry<String, EvalResult> entry : candidateByQuery.entrySet()) {
            EvalResult candidateItem = entry.getValue();
            EvalResult baselineItem = baselineByQuery.get(entry.getKey());
            if (baselineItem == null) {
                continue;
            }
            int rankDelta = normalizeRankDelta(baselineItem.rank(), candidateItem.rank());
            if (rankDelta > 0) {
                improvedCount++;
            } else if (rankDelta < 0) {
                regressedCount++;
            }
            if (baselineItem.hit() != candidateItem.hit() || baselineItem.rank() != candidateItem.rank()) {
                changedCases.add(new EvalCaseComparison(
                        candidateItem.query(),
                        baselineItem.hit(),
                        candidateItem.hit(),
                        baselineItem.rank(),
                        candidateItem.rank(),
                        rankDelta
                ));
            }
        }

        return new RetrievalEvalComparison(
                baseline.runId(),
                candidate.runId(),
                new EvalMetricDelta(
                        baseline.recallAt1(),
                        candidate.recallAt1(),
                        candidate.recallAt1() - baseline.recallAt1()
                ),
                new EvalMetricDelta(
                        baseline.recallAt3(),
                        candidate.recallAt3(),
                        candidate.recallAt3() - baseline.recallAt3()
                ),
                new EvalMetricDelta(
                        baseline.recallAt5(),
                        candidate.recallAt5(),
                        candidate.recallAt5() - baseline.recallAt5()
                ),
                new EvalMetricDelta(
                        baseline.mrr(),
                        candidate.mrr(),
                        candidate.mrr() - baseline.mrr()
                ),
                improvedCount,
                regressedCount,
                changedCases
        );
    }

    /**
     * 聚合最近若干次评测运行的趋势摘要。
     *
     * @param limit 返回的历史窗口大小
     * @return 趋势聚合结果
     */
    public RetrievalEvalTrend getTrend(int limit) {
        ensureEvalEnabled();
        List<RetrievalEvalRunSummary> runs = listRecentRuns(limit);
        if (runs.isEmpty()) {
            return new RetrievalEvalTrend(List.of(), 0.0D, 0.0D, 0.0D, Map.of());
        }
        double avgRecallAt5 = runs.stream().mapToDouble(RetrievalEvalRunSummary::recallAt5).average().orElse(0.0D);
        double avgMrr = runs.stream().mapToDouble(RetrievalEvalRunSummary::mrr).average().orElse(0.0D);
        double bestRecallAt5 = runs.stream().mapToDouble(RetrievalEvalRunSummary::recallAt5).max().orElse(0.0D);
        Map<String, Long> experimentDistribution = runs.stream()
                .map(item -> normalizeText(item.experimentTag(), "default"))
                .collect(Collectors.groupingBy(item -> item, LinkedHashMap::new, Collectors.counting()));
        return new RetrievalEvalTrend(runs, avgRecallAt5, avgMrr, bestRecallAt5, experimentDistribution);
    }

    /**
     * 对指定运行的失败样本按标签聚类，便于快速识别哪类主题回退最多。
     *
     * @param runId 运行 ID
     * @return 聚类结果；运行不存在时返回 null
     */
    public List<RetrievalEvalFailureCluster> clusterFailures(String runId) {
        ensureEvalEnabled();
        RetrievalEvalReport report = getRunDetail(runId);
        if (report == null) {
            return null;
        }
        Map<String, List<EvalResult>> grouped = report.results().stream()
                .filter(item -> !item.hit())
                .collect(Collectors.groupingBy(
                        item -> normalizeText(item.tag(), "unknown"),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        return grouped.entrySet().stream()
                .map(entry -> new RetrievalEvalFailureCluster(
                        entry.getKey(),
                        entry.getValue().size(),
                        entry.getValue().stream().map(EvalResult::query).limit(5).toList(),
                        entry.getValue().stream()
                                .flatMap(item -> item.expectedKeywords().stream())
                                .filter(keyword -> keyword != null && !keyword.isBlank())
                                .distinct()
                                .limit(5)
                                .toList()
                ))
                .toList();
    }

    /**
     * 返回内置参数模板，帮助前端快速发起标准化实验。
     *
     * @return 参数模板列表
     */
    public List<RetrievalEvalParameterTemplate> listParameterTemplates() {
        ensureEvalEnabled();
        return List.of(
                new RetrievalEvalParameterTemplate(
                        "baseline-rrf",
                        "Baseline RRF",
                        "默认基线模板，适合与其他参数组做 A/B 对比。",
                        Map.of("fusionMode", "RRF", "rerankTopK", 8, "allowWebFallback", false)
                ),
                new RetrievalEvalParameterTemplate(
                        "high-rerank",
                        "High Rerank",
                        "提高 rerank 截断窗口，适合验证重排阶段是否存在截断损失。",
                        Map.of("fusionMode", "RRF", "rerankTopK", 15, "allowWebFallback", false)
                ),
                new RetrievalEvalParameterTemplate(
                        "fallback-observe",
                        "Fallback Observe",
                        "保留 fallback 观测参数，适合检查低质量阈值是否过严。",
                        Map.of("fusionMode", "RRF", "rerankTopK", 8, "allowWebFallback", true, "webFallbackMode", "ON_LOW_QUALITY")
                )
        );
    }

    /**
     * 返回当前评测开关状态。
     *
     * @return true 表示评测能力启用
     */
    public boolean isEvalEnabled() {
        return observabilitySwitchProperties.isRetrievalEvalEnabled();
    }

    /**
     * 统一校验评测开关，避免关闭状态下仍然触发重评测或历史查询。
     */
    private void ensureEvalEnabled() {
        if (!observabilitySwitchProperties.isRetrievalEvalEnabled()) {
            throw new IllegalStateException("召回率评测已关闭，请设置 app.observability.retrieval-eval-enabled=true 后重试。");
        }
    }

    /**
     * 将评测结果归档到内存历史，并尽力持久化到数据库。
     *
     * @param report 评测报告
     * @param cases 原始评测用例
     */
    private void archiveReport(RetrievalEvalReport report, List<EvalCase> cases) {
        if (report == null) {
            return;
        }
        reportHistory.removeIf(item -> Objects.equals(item.runId(), report.runId()));
        reportHistory.addFirst(report);
        reportDetailHistory.put(report.runId(), report);
        while (reportHistory.size() > MAX_REPORT_HISTORY) {
            RetrievalEvalReport removed = reportHistory.removeLast();
            reportDetailHistory.remove(removed.runId());
        }
        persistReport(report, cases);
    }

    /**
     * 持久化评测运行及样本结果。
     *
     * @param report 评测报告
     * @param cases 原始评测用例
     */
    private void persistReport(RetrievalEvalReport report, List<EvalCase> cases) {
        if (!persistenceAvailable() || report == null) {
            return;
        }
        try {
            RetrievalEvalRunDO existingRun = retrievalEvalRunMapper.selectOne(new LambdaQueryWrapper<RetrievalEvalRunDO>()
                    .eq(RetrievalEvalRunDO::getRunId, report.runId())
                    .last("LIMIT 1"));
            RetrievalEvalRunDO runDO = toRunDO(report);
            if (existingRun == null) {
                retrievalEvalRunMapper.insert(runDO);
            } else {
                runDO.setId(existingRun.getId());
                retrievalEvalRunMapper.updateById(runDO);
            }

            retrievalEvalCaseMapper.delete(new LambdaQueryWrapper<RetrievalEvalCaseDO>()
                    .eq(RetrievalEvalCaseDO::getRunId, report.runId()));
            for (int i = 0; i < report.results().size(); i++) {
                EvalResult result = report.results().get(i);
                EvalCase evalCase = i < cases.size() ? cases.get(i) : new EvalCase(result.query(), result.expectedKeywords(), report.datasetSource());
                retrievalEvalCaseMapper.insert(toCaseDO(report.runId(), i, evalCase, result));
            }
        } catch (Exception ignored) {
            // 评测持久化失败时保留内存历史，不中断主接口返回。
        }
    }

    /**
     * 从数据库读取最近的评测运行摘要。
     *
     * @param limit 返回上限
     * @return 运行摘要列表
     */
    private List<RetrievalEvalRunSummary> loadRecentRunsFromPersistence(int limit) {
        if (!persistenceAvailable()) {
            return List.of();
        }
        try {
            return retrievalEvalRunMapper.selectList(new LambdaQueryWrapper<RetrievalEvalRunDO>()
                            .orderByDesc(RetrievalEvalRunDO::getCreatedAt, RetrievalEvalRunDO::getId)
                            .last("LIMIT " + Math.max(1, Math.min(limit, MAX_REPORT_HISTORY))))
                    .stream()
                    .map(this::toRunSummary)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * 从数据库加载单次评测详情。
     *
     * @param runId 运行 ID
     * @return 评测详情；不存在时返回 null
     */
    private RetrievalEvalReport loadRunDetailFromPersistence(String runId) {
        if (!persistenceAvailable()) {
            return null;
        }
        try {
            RetrievalEvalRunDO runDO = retrievalEvalRunMapper.selectOne(new LambdaQueryWrapper<RetrievalEvalRunDO>()
                    .eq(RetrievalEvalRunDO::getRunId, runId)
                    .last("LIMIT 1"));
            if (runDO == null) {
                return null;
            }
            List<RetrievalEvalCaseDO> caseRows = retrievalEvalCaseMapper.selectList(new LambdaQueryWrapper<RetrievalEvalCaseDO>()
                    .eq(RetrievalEvalCaseDO::getRunId, runId)
                    .orderByAsc(RetrievalEvalCaseDO::getCaseIndex, RetrievalEvalCaseDO::getId));
            return toReport(runDO, caseRows);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 将领域报告转换为数据库汇总实体。
     *
     * @param report 评测报告
     * @return 汇总实体
     */
    private RetrievalEvalRunDO toRunDO(RetrievalEvalReport report) {
        RetrievalEvalRunDO runDO = new RetrievalEvalRunDO();
        runDO.setRunId(report.runId());
        runDO.setDatasetSource(report.datasetSource());
        runDO.setRunLabel(report.runLabel());
        runDO.setExperimentTag(report.experimentTag());
        runDO.setParameterSnapshot(report.parameterSnapshot());
        runDO.setNotes(report.notes());
        runDO.setTotalCases(report.totalCases());
        runDO.setHitCases(report.hitCases());
        runDO.setRecallAt1(report.recallAt1());
        runDO.setRecallAt3(report.recallAt3());
        runDO.setRecallAt5(report.recallAt5());
        runDO.setMrr(report.mrr());
        runDO.setReportTimestamp(report.timestamp());
        return runDO;
    }

    /**
     * 将单样本结果转换为数据库实体。
     *
     * @param runId 运行 ID
     * @param caseIndex 样本序号
     * @param evalCase 原始用例
     * @param result 评测结果
     * @return 样本结果实体
     */
    private RetrievalEvalCaseDO toCaseDO(String runId, int caseIndex, EvalCase evalCase, EvalResult result) {
        RetrievalEvalCaseDO caseDO = new RetrievalEvalCaseDO();
        caseDO.setRunId(runId);
        caseDO.setCaseIndex(caseIndex);
        caseDO.setQuery(result.query());
        caseDO.setExpectedKeywords(result.expectedKeywords());
        caseDO.setTag(result.tag() == null || result.tag().isBlank() ? (evalCase.tag() == null ? "" : evalCase.tag()) : result.tag());
        caseDO.setHit(result.hit());
        caseDO.setRank(result.rank());
        caseDO.setRetrievedSnippets(result.retrievedSnippets());
        return caseDO;
    }

    /**
     * 将数据库汇总实体转换为运行摘要。
     *
     * @param runDO 汇总实体
     * @return 运行摘要
     */
    private RetrievalEvalRunSummary toRunSummary(RetrievalEvalRunDO runDO) {
        return new RetrievalEvalRunSummary(
                runDO.getRunId(),
                runDO.getReportTimestamp(),
                runDO.getDatasetSource(),
                runDO.getRunLabel(),
                runDO.getExperimentTag(),
                runDO.getParameterSnapshot() == null ? Map.of() : runDO.getParameterSnapshot(),
                runDO.getNotes() == null ? "" : runDO.getNotes(),
                safeInt(runDO.getTotalCases()),
                safeInt(runDO.getHitCases()),
                safeDouble(runDO.getRecallAt1()),
                safeDouble(runDO.getRecallAt3()),
                safeDouble(runDO.getRecallAt5()),
                safeDouble(runDO.getMrr())
        );
    }

    /**
     * 将领域报告转换为运行摘要。
     *
     * @param report 领域报告
     * @return 运行摘要
     */
    private RetrievalEvalRunSummary toRunSummary(RetrievalEvalReport report) {
        return new RetrievalEvalRunSummary(
                report.runId(),
                report.timestamp(),
                report.datasetSource(),
                report.runLabel(),
                report.experimentTag(),
                report.parameterSnapshot(),
                report.notes(),
                report.totalCases(),
                report.hitCases(),
                report.recallAt1(),
                report.recallAt3(),
                report.recallAt5(),
                report.mrr()
        );
    }

    /**
     * 将数据库结果还原为领域报告。
     *
     * @param runDO 汇总实体
     * @param caseRows 样本实体列表
     * @return 领域报告
     */
    private RetrievalEvalReport toReport(RetrievalEvalRunDO runDO, List<RetrievalEvalCaseDO> caseRows) {
        List<EvalResult> results = caseRows == null ? List.of() : caseRows.stream()
                .map(item -> new EvalResult(
                        item.getQuery(),
                        item.getTag(),
                        item.getExpectedKeywords() == null ? List.of() : item.getExpectedKeywords(),
                        Boolean.TRUE.equals(item.getHit()),
                        item.getRank() == null ? -1 : item.getRank(),
                        item.getRetrievedSnippets() == null ? List.of() : item.getRetrievedSnippets()
                ))
                .toList();
        return new RetrievalEvalReport(
                runDO.getRunId(),
                runDO.getReportTimestamp(),
                runDO.getDatasetSource(),
                runDO.getRunLabel(),
                runDO.getExperimentTag(),
                runDO.getParameterSnapshot() == null ? Map.of() : runDO.getParameterSnapshot(),
                runDO.getNotes() == null ? "" : runDO.getNotes(),
                safeInt(runDO.getTotalCases()),
                safeInt(runDO.getHitCases()),
                safeDouble(runDO.getRecallAt1()),
                safeDouble(runDO.getRecallAt3()),
                safeDouble(runDO.getRecallAt5()),
                safeDouble(runDO.getMrr()),
                results
        );
    }

    /**
     * 统一规范文本字段。
     *
     * @param value 原始值
     * @param defaultValue 默认值
     * @return 规范化结果
     */
    private String normalizeText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    /**
     * 排名差异按“命中提升为正、退化为负”计算。
     *
     * @param baselineRank 基线排名
     * @param candidateRank 候选排名
     * @return 差异值
     */
    private int normalizeRankDelta(int baselineRank, int candidateRank) {
        if (baselineRank <= 0 && candidateRank <= 0) {
            return 0;
        }
        if (baselineRank <= 0) {
            return 1;
        }
        if (candidateRank <= 0) {
            return -1;
        }
        return baselineRank - candidateRank;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private double safeDouble(Double value) {
        return value == null ? 0.0D : value;
    }

    private boolean persistenceAvailable() {
        return retrievalEvalRunMapper != null && retrievalEvalCaseMapper != null;
    }

    private EvalRunOptions buildDatasetRunOptions(String datasetFile, String experimentTag, String notes) {
        String normalizedDatasetFile = normalizeDatasetFilename(datasetFile);
        String datasetSource = stripJsonSuffix(normalizedDatasetFile);
        return new EvalRunOptions(
                normalizedDatasetFile,
                datasetSource,
                experimentTag,
                Map.of("dataset", datasetSource, "datasetFile", normalizedDatasetFile),
                notes
        );
    }

    private String resolveDatasetFilename(String dataset) {
        if (dataset == null || dataset.isBlank()) {
            return DEFAULT_DATASET_FILE;
        }
        String normalized = dataset.trim().toLowerCase(Locale.ROOT);
        if (DATASET_FILE_MAPPING.containsKey(normalized)) {
            return DATASET_FILE_MAPPING.get(normalized);
        }
        return normalizeDatasetFilename(dataset);
    }

    private String normalizeDatasetFilename(String dataset) {
        String candidate = dataset == null ? DEFAULT_DATASET_FILE : dataset.trim();
        if (candidate.isBlank()) {
            return DEFAULT_DATASET_FILE;
        }
        if (!candidate.endsWith(".json")) {
            candidate = candidate + ".json";
        }
        if (!candidate.startsWith("rag_ground_truth")) {
            throw new IllegalArgumentException("不支持的检索评测数据集: " + dataset);
        }
        return candidate;
    }

    private String stripJsonSuffix(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith(".json") ? value.substring(0, value.length() - 5) : value;
    }

    /**
     * 评测运行完整报告。
     *
     * @param runId 运行 ID
     * @param timestamp 生成时间
     * @param datasetSource 数据集来源
     * @param runLabel 展示标签
     * @param totalCases 总样本数
     * @param hitCases Recall@5 命中样本数
     * @param recallAt1 Recall@1
     * @param recallAt3 Recall@3
     * @param recallAt5 Recall@5
     * @param mrr MRR
     * @param results 逐样本结果
     */
    public record RetrievalEvalReport(
            String runId,
            String timestamp,
            String datasetSource,
            String runLabel,
            String experimentTag,
            Map<String, Object> parameterSnapshot,
            String notes,
            int totalCases,
            int hitCases,
            double recallAt1,
            double recallAt3,
            double recallAt5,
            double mrr,
            List<EvalResult> results
    ) {
    }

    /**
     * 评测运行摘要，供历史列表展示。
     */
    public record RetrievalEvalRunSummary(
            String runId,
            String timestamp,
            String datasetSource,
            String runLabel,
            String experimentTag,
            Map<String, Object> parameterSnapshot,
            String notes,
            int totalCases,
            int hitCases,
            double recallAt1,
            double recallAt3,
            double recallAt5,
            double mrr
    ) {
    }

    /**
     * 单条评测结果。
     */
    public record EvalResult(
            String query,
            String tag,
            List<String> expectedKeywords,
            boolean hit,
            int rank,
            List<String> retrievedSnippets
    ) {
    }

    /**
     * 评测用例定义。
     */
    public record EvalCase(String query, List<String> expectedKeywords, String tag) {
    }

    /**
     * 评测运行配置。
     *
     * @param datasetSource 数据集来源
     * @param runLabel 运行标签
     * @param experimentTag 实验标签
     * @param parameterSnapshot 参数快照
     * @param notes 备注说明
     */
    public record EvalRunOptions(
            String datasetSource,
            String runLabel,
            String experimentTag,
            Map<String, Object> parameterSnapshot,
            String notes
    ) {
    }

    /**
     * 两次评测运行的对比结果。
     */
    public record RetrievalEvalComparison(
            String baselineRunId,
            String candidateRunId,
            EvalMetricDelta recallAt1,
            EvalMetricDelta recallAt3,
            EvalMetricDelta recallAt5,
            EvalMetricDelta mrr,
            int improvedCaseCount,
            int regressedCaseCount,
            List<EvalCaseComparison> changedCases
    ) {
    }

    /**
     * 指标对比差值结构。
     */
    public record EvalMetricDelta(double baseline, double candidate, double delta) {
    }

    /**
     * 单样本变化结构。
     */
    public record EvalCaseComparison(
            String query,
            boolean baselineHit,
            boolean candidateHit,
            int baselineRank,
            int candidateRank,
            int rankDelta
    ) {
    }

    /**
     * 评测趋势聚合结果。
     */
    public record RetrievalEvalTrend(
            List<RetrievalEvalRunSummary> runs,
            double avgRecallAt5,
            double avgMrr,
            double bestRecallAt5,
            Map<String, Long> experimentDistribution
    ) {
    }

    /**
     * 失败样本聚类结果。
     */
    public record RetrievalEvalFailureCluster(
            String tag,
            int failedCaseCount,
            List<String> sampleQueries,
            List<String> representativeKeywords
    ) {
    }

    /**
     * 参数模板定义。
     */
    public record RetrievalEvalParameterTemplate(
            String templateId,
            String title,
            String description,
            Map<String, Object> parameterSnapshot
    ) {
    }

    /**
     * 内置数据集定义。
     */
    public record EvalDatasetDefinition(
            String datasetId,
            String fileName,
            String title,
            String description
    ) {
    }
}






