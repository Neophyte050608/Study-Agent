package io.github.imzmq.interview.interview.api;

import io.github.imzmq.interview.interview.api.support.ControllerHelper;
import io.github.imzmq.interview.interview.application.InterviewService;
import io.github.imzmq.interview.knowledge.application.evaluation.RAGQualityEvaluationService;
import io.github.imzmq.interview.knowledge.application.evaluation.RetrievalEvaluationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api")
public class EvaluationObservabilityController {

    private final InterviewService interviewService;
    private final RetrievalEvaluationService retrievalEvaluationService;

    public EvaluationObservabilityController(
            InterviewService interviewService,
            RetrievalEvaluationService retrievalEvaluationService
    ) {
        this.interviewService = interviewService;
        this.retrievalEvaluationService = retrievalEvaluationService;
    }

    /**
     * 获取检索指标聚合（Top-3 召回率 + 检索节点 P95/P99 延迟）。
     */
    @GetMapping("/observability/retrieval-metrics")
    public ResponseEntity<?> retrievalMetrics(
            @RequestParam(value = "limit", required = false, defaultValue = "200") Integer limit,
            @RequestParam(value = "hours", required = false) Integer hours,
            @RequestParam(value = "dataset", required = false) String dataset
    ) {
        return ResponseEntity.ok(interviewService.getRetrievalMetrics(limit, hours, dataset));
    }

    /**
     * 运行检索离线评测（默认用例）。
     */
    @GetMapping("/observability/retrieval-eval")
    public ResponseEntity<?> retrievalEval(
            @RequestParam(value = "dataset", required = false) String dataset
    ) {
        try {
            return ResponseEntity.ok(interviewService.runRetrievalOfflineEval(dataset));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 查询最近的检索评测运行历史。
     */
    @GetMapping("/observability/retrieval-eval/runs")
    public ResponseEntity<?> listRetrievalEvalRuns(
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit
    ) {
        try {
            int normalizedLimit = limit == null ? 20 : limit;
            return ResponseEntity.ok(Map.of(
                    "limit", normalizedLimit,
                    "records", interviewService.listRecentRetrievalEvalRuns(normalizedLimit)
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 查询单次检索评测详情。
     */
    @GetMapping("/observability/retrieval-eval/runs/{runId}")
    public ResponseEntity<?> getRetrievalEvalRunDetail(@PathVariable("runId") String runId) {
        try {
            RetrievalEvaluationService.RetrievalEvalReport report = interviewService.getRetrievalEvalRunDetail(runId);
            if (report == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "未找到对应的检索评测运行"));
            }
            return ResponseEntity.ok(report);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 对比两次检索评测运行结果。
     */
    @GetMapping("/observability/retrieval-eval/compare")
    public ResponseEntity<?> compareRetrievalEvalRuns(
            @RequestParam("baselineRunId") String baselineRunId,
            @RequestParam("candidateRunId") String candidateRunId
    ) {
        try {
            RetrievalEvaluationService.RetrievalEvalComparison comparison = interviewService.compareRetrievalEvalRuns(baselineRunId, candidateRunId);
            if (comparison == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "未找到可对比的检索评测运行"));
            }
            return ResponseEntity.ok(comparison);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 查询检索评测趋势摘要。
     */
    @GetMapping("/observability/retrieval-eval/trend")
    public ResponseEntity<?> getRetrievalEvalTrend(
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit
    ) {
        try {
            int normalizedLimit = limit == null ? 20 : limit;
            return ResponseEntity.ok(interviewService.getRetrievalEvalTrend(normalizedLimit));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 查询指定评测运行的失败样本聚类结果。
     */
    @GetMapping("/observability/retrieval-eval/runs/{runId}/failure-clusters")
    public ResponseEntity<?> getRetrievalEvalFailureClusters(@PathVariable("runId") String runId) {
        try {
            List<RetrievalEvaluationService.RetrievalEvalFailureCluster> clusters = interviewService.clusterRetrievalEvalFailures(runId);
            if (clusters == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "未找到对应的检索评测运行"));
            }
            return ResponseEntity.ok(Map.of(
                    "runId", runId,
                    "clusters", clusters
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 获取检索评测参数模板列表。
     */
    @GetMapping("/observability/retrieval-eval/templates")
    public ResponseEntity<?> listRetrievalEvalTemplates() {
        try {
            return ResponseEntity.ok(Map.of(
                    "records", interviewService.listRetrievalEvalParameterTemplates()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 获取检索评测内置数据集列表。
     */
    @GetMapping("/observability/retrieval-eval/datasets")
    public ResponseEntity<?> listRetrievalEvalDatasets() {
        try {
            return ResponseEntity.ok(Map.of(
                    "records", interviewService.listRetrievalEvalDatasets()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 运行带自定义用例的检索评测。
     */
    @PostMapping("/observability/retrieval-eval/run")
    public ResponseEntity<?> runRetrievalEval(@RequestBody Map<String, Object> payload) {
        try {
            List<RetrievalEvaluationService.EvalCase> cases = parseEvalCases(payload.get("cases"));
            if (cases.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "评测用例为空，请提供 cases"));
            }
            RetrievalEvaluationService.EvalRunOptions options = new RetrievalEvaluationService.EvalRunOptions(
                    ControllerHelper.stringifyValue(payload.get("datasetSource")),
                    ControllerHelper.stringifyValue(payload.get("runLabel")),
                    ControllerHelper.stringifyValue(payload.get("experimentTag")),
                    ControllerHelper.extractParameterSnapshot(payload.get("parameterSnapshot")),
                    ControllerHelper.stringifyValue(payload.get("notes"))
            );
            return ResponseEntity.ok(interviewService.runRetrievalEvalWithCases(cases, options));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 上传 CSV 评测集并运行检索评测。
     */
    @PostMapping("/observability/retrieval-eval/upload")
    public ResponseEntity<?> uploadRetrievalEval(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "请上传评测集文件"));
        }
        try {
            String text = new String(file.getBytes(), StandardCharsets.UTF_8);
            List<RetrievalEvaluationService.EvalCase> cases = retrievalEvaluationService.parseCasesFromCsv(text);
            return ResponseEntity.ok(retrievalEvaluationService.runCustomEval(
                    cases,
                    new RetrievalEvaluationService.EvalRunOptions("csv", "csv-upload", "", Map.of(), "CSV 上传评测")
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "评测集文件读取失败"));
        }
    }

    /**
     * 运行 RAG 生成质量评测（默认数据集）。
     */
    @GetMapping("/observability/rag-quality-eval")
    public ResponseEntity<?> runRagQualityEval(
            @RequestParam(value = "dataset", required = false) String dataset,
            @RequestParam(value = "engine", required = false) String engine
    ) {
        try {
            return ResponseEntity.ok(interviewService.runRAGQualityEval(dataset, engine));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 获取 RAG 生成质量评测内置数据集列表。
     */
    @GetMapping("/observability/rag-quality-eval/datasets")
    public ResponseEntity<?> listRagQualityEvalDatasets() {
        try {
            return ResponseEntity.ok(Map.of(
                    "records", interviewService.listRAGQualityEvalDatasets()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 运行自定义数据集 RAG 生成质量评测。
     */
    @PostMapping("/observability/rag-quality-eval/run")
    public ResponseEntity<?> runRagQualityEvalWithCases(@RequestBody Map<String, Object> payload) {
        try {
            List<RAGQualityEvaluationService.QualityEvalCase> cases = parseQualityEvalCases(payload.get("cases"));
            if (cases.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "评测用例为空，请提供 cases"));
            }

            Object rawOptions = payload.get("options");
            String datasetSource = ControllerHelper.stringifyValue(payload.get("datasetSource"));
            String runLabel = ControllerHelper.stringifyValue(payload.get("runLabel"));
            String experimentTag = ControllerHelper.stringifyValue(payload.get("experimentTag"));
            Map<String, Object> parameterSnapshot = ControllerHelper.extractParameterSnapshot(payload.get("parameterSnapshot"));
            String notes = ControllerHelper.stringifyValue(payload.get("notes"));
            if (rawOptions instanceof Map<?, ?> map) {
                datasetSource = ControllerHelper.stringifyValue(map.get("datasetSource"));
                runLabel = ControllerHelper.stringifyValue(map.get("runLabel"));
                experimentTag = ControllerHelper.stringifyValue(map.get("experimentTag"));
                parameterSnapshot = ControllerHelper.extractParameterSnapshot(map.get("parameterSnapshot"));
                notes = ControllerHelper.stringifyValue(map.get("notes"));
            }

            RAGQualityEvaluationService.EvalRunOptions options = new RAGQualityEvaluationService.EvalRunOptions(
                    datasetSource,
                    runLabel,
                    experimentTag,
                    parameterSnapshot,
                    notes
            );
            String engine = ControllerHelper.stringifyValue(payload.get("engine"));
            return ResponseEntity.ok(interviewService.runRAGQualityEvalWithCases(cases, options, engine));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 查询 RAG 生成质量评测历史列表。
     */
    @GetMapping("/observability/rag-quality-eval/runs")
    public ResponseEntity<?> listRagQualityEvalRuns(
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit
    ) {
        try {
            int normalizedLimit = limit == null ? 20 : limit;
            return ResponseEntity.ok(Map.of(
                    "limit", normalizedLimit,
                    "records", interviewService.listRecentRAGQualityEvalRuns(normalizedLimit)
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 查询单次 RAG 生成质量评测详情。
     */
    @GetMapping("/observability/rag-quality-eval/runs/{runId}")
    public ResponseEntity<?> getRagQualityEvalRunDetail(@PathVariable("runId") String runId) {
        try {
            RAGQualityEvaluationService.QualityEvalReport report = interviewService.getRAGQualityEvalRunDetail(runId);
            if (report == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "未找到对应的RAG生成质量评测运行"));
            }
            return ResponseEntity.ok(report);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 对比两次 RAG 生成质量评测运行结果。
     */
    @GetMapping("/observability/rag-quality-eval/compare")
    public ResponseEntity<?> compareRagQualityEvalRuns(
            @RequestParam("baselineRunId") String baselineRunId,
            @RequestParam("candidateRunId") String candidateRunId
    ) {
        try {
            RAGQualityEvaluationService.QualityEvalComparison comparison =
                    interviewService.compareRAGQualityEvalRuns(baselineRunId, candidateRunId);
            if (comparison == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "未找到可对比的RAG生成质量评测运行"));
            }
            return ResponseEntity.ok(comparison);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 查询 RAG 生成质量评测趋势摘要。
     */
    @GetMapping("/observability/rag-quality-eval/trend")
    public ResponseEntity<?> getRagQualityEvalTrend(
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit
    ) {
        try {
            int normalizedLimit = limit == null ? 20 : limit;
            return ResponseEntity.ok(interviewService.getRAGQualityEvalTrend(normalizedLimit));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 获取 RAG 生成质量评测引擎状态。
     */
    @GetMapping("/observability/rag-quality-eval/engine-status")
    public ResponseEntity<?> getRagQualityEvalEngineStatus() {
        try {
            return ResponseEntity.ok(interviewService.getRAGQualityEvalEngineStatus());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 解析评测用例对象。
     */
    private List<RetrievalEvaluationService.EvalCase> parseEvalCases(Object rawCases) {
        if (!(rawCases instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> {
                    if (!(item instanceof Map<?, ?> map)) {
                        return null;
                    }
                    Object queryObj = map.get("query");
                    String query = queryObj == null ? "" : queryObj.toString().trim();
                    if (query.isBlank()) {
                        return null;
                    }
                    Object keywordsObj = map.get("expectedKeywords");
                    List<String> keywords;
                    if (keywordsObj instanceof List<?> keyList) {
                        keywords = keyList.stream()
                                .map(Object::toString)
                                .map(String::trim)
                                .filter(word -> !word.isBlank())
                                .toList();
                    } else {
                        keywords = List.of();
                    }
                    String tag = map.get("tag") == null ? "manual" : String.valueOf(map.get("tag")).trim();
                    return new RetrievalEvaluationService.EvalCase(query, keywords, tag.isBlank() ? "manual" : tag);
                })
                .filter(item -> item != null)
                .toList();
    }

    /**
     * 解析 RAG 生成质量评测用例对象。
     */
    private List<RAGQualityEvaluationService.QualityEvalCase> parseQualityEvalCases(Object rawCases) {
        if (!(rawCases instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> {
                    if (!(item instanceof Map<?, ?> map)) {
                        return null;
                    }
                    String query = ControllerHelper.stringifyValue(map.get("query"));
                    if (query == null || query.isBlank()) {
                        return null;
                    }
                    String groundTruthAnswer = ControllerHelper.stringifyValue(map.get("groundTruthAnswer"));
                    Object rawConcepts = map.get("groundTruthKeyConcepts");
                    List<String> concepts;
                    if (rawConcepts instanceof List<?> conceptList) {
                        concepts = conceptList.stream()
                                .map(Object::toString)
                                .map(String::trim)
                                .filter(word -> !word.isBlank())
                                .toList();
                    } else {
                        concepts = List.of();
                    }
                    String tag = ControllerHelper.stringifyValue(map.get("tag"));
                    return new RAGQualityEvaluationService.QualityEvalCase(
                            query.trim(),
                            groundTruthAnswer == null ? "" : groundTruthAnswer,
                            concepts,
                            tag == null || tag.isBlank() ? "manual" : tag
                    );
                })
                .filter(Objects::nonNull)
                .toList();
    }
}
