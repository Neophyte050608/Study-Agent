package io.github.imzmq.interview.knowledge.application.evaluation;

import io.github.imzmq.interview.agent.application.context.AgentContextAssembler;
import io.github.imzmq.interview.agent.application.context.AgentContextMode;
import io.github.imzmq.interview.agent.application.context.AgentContextQuery;
import io.github.imzmq.interview.agent.application.context.AgentRuntimeContext;
import io.github.imzmq.interview.agent.application.context.InterviewContextAttributes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.imzmq.interview.agent.application.AgentSkillService;
import io.github.imzmq.interview.chat.application.JsonResult;
import io.github.imzmq.interview.chat.application.LlmJsonParser;
import io.github.imzmq.interview.chat.application.PromptManager;
import io.github.imzmq.interview.chat.application.PromptTemplateService;
import io.github.imzmq.interview.config.observability.ObservabilitySwitchProperties;
import io.github.imzmq.interview.knowledge.application.RAGService;
import io.github.imzmq.interview.knowledge.application.observability.RAGObservabilityService;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeDefinitions;
import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.observability.core.RAGTraceContext;
import io.github.imzmq.interview.skill.core.SkillExecutionContext;
import io.github.imzmq.interview.skill.core.SkillExecutionResult;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 面试回答评估服务。
 *
 * <p>负责基于已构建的 {@link RAGService.KnowledgePacket} 生成结构化评估、执行证据引用校验，
 * 并在模型异常时返回可继续前端流程的降级 JSON。</p>
 */
@Service
public class InterviewAnswerEvaluationService {

    private static final Logger logger = LoggerFactory.getLogger(InterviewAnswerEvaluationService.class);
    private static final Pattern EVIDENCE_LINE_PATTERN = Pattern.compile("^(\\d+)\\.\\s+(.*)$");
    private static final Pattern INDEX_PATTERN = Pattern.compile("(\\d+)");
    private static final Pattern RAW_API_KEY_PATTERN = Pattern.compile("(?i)(\"?api[-_ ]?key\"?\\s*[:=]\\s*\"?)([^\",\\s]+)");
    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)([A-Za-z0-9._-]{8,})");
    private static final Pattern LONG_TOKEN_PATTERN = Pattern.compile("\\b[A-Za-z0-9._-]{32,}\\b");

    private final RoutingChatService routingChatService;
    private final RAGObservabilityService observabilityService;
    private final AgentSkillService agentSkillService;
    private final PromptTemplateService promptTemplateService;
    private final PromptManager promptManager;
    private final ObservabilitySwitchProperties observabilitySwitchProperties;
    private final SkillOrchestrator skillOrchestrator;
    private final LlmJsonParser llmJsonParser;
    private final AgentContextAssembler contextAssembler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InterviewAnswerEvaluationService(RoutingChatService routingChatService,
                                            RAGObservabilityService observabilityService,
                                            AgentSkillService agentSkillService,
                                            PromptTemplateService promptTemplateService,
                                            PromptManager promptManager,
                                            ObservabilitySwitchProperties observabilitySwitchProperties,
                                            SkillOrchestrator skillOrchestrator,
                                            LlmJsonParser llmJsonParser,
                                            AgentContextAssembler contextAssembler) {
        this.routingChatService = routingChatService;
        this.observabilityService = observabilityService;
        this.agentSkillService = agentSkillService;
        this.promptTemplateService = promptTemplateService;
        this.promptManager = promptManager;
        this.observabilitySwitchProperties = observabilitySwitchProperties;
        this.skillOrchestrator = skillOrchestrator;
        this.llmJsonParser = llmJsonParser;
        this.contextAssembler = contextAssembler;
    }

    /**
     * 执行回答评估并校验 citations/conflicts 只能引用知识包中的证据编号。
     */
    public String evaluateAndValidate(String topic,
                                      String question,
                                      String userAnswer,
                                      String difficultyLevel,
                                      String followUpState,
                                      double topicMastery,
                                      String profileSnapshot,
                                      String strategyHint,
                                      RAGService.KnowledgePacket packet) {
        try {
            RAGService.EvaluationResult result = evaluateWithKnowledge(
                    topic,
                    question,
                    userAnswer,
                    difficultyLevel,
                    followUpState,
                    topicMastery,
                    profileSnapshot,
                    strategyHint,
                    packet
            );
            String retrievalEvidence = packet == null ? "[]" : packet.retrievalEvidence();
            return validateEvidenceReferences(result.json(), retrievalEvidence);
        } catch (RuntimeException e) {
            logger.warn("回答评估失败，返回降级结果。原因: {}", summarizeError(e));
            return buildFallbackEvaluation(question, e);
        }
    }

    /**
     * 基于检索上下文执行回答评估（LLM 生成）。
     */
    public RAGService.EvaluationResult evaluateWithKnowledge(String topic,
                                                             String question,
                                                             String userAnswer,
                                                             String difficultyLevel,
                                                             String followUpState,
                                                             double topicMastery,
                                                             String profileSnapshot,
                                                             String strategyHint,
                                                             RAGService.KnowledgePacket packet) {
        return executeWithinTraceRoot("KNOWLEDGE_EVAL", "Knowledge Evaluation", "Q: " + question, () -> {
            String originalContext = packet == null ? "" : packet.context();
            String originalImageContext = packet == null ? "" : packet.imageContext();
            String originalEvidence = packet == null ? "[]" : packet.retrievalEvidence();
            String finalContext = truncate(originalContext, 1400);
            String finalImageContext = truncate(originalImageContext, 600);
            String finalEvidence = truncate(originalEvidence, 900);
            String safeProfileSnapshot = truncate(profileSnapshot, 480);
            String normalizedStrategy = strategyHint == null ? "" : strategyHint.trim();
            if (normalizedStrategy.isBlank()) {
                normalizedStrategy = resolveQuestionStrategySummary(
                        "evaluation",
                        topic,
                        question,
                        difficultyLevel,
                        followUpState,
                        safeProfileSnapshot,
                        "",
                        false
                );
            }
            String assembledContext = assembleInterviewContext(
                    topic,
                    question,
                    userAnswer,
                    difficultyLevel,
                    followUpState,
                    topicMastery,
                    safeProfileSnapshot,
                    normalizedStrategy,
                    packet,
                    finalContext
            );
            if (observabilitySwitchProperties.isRagTraceEnabled()) {
                logger.info(
                        "评估调用参数: topic={}, difficulty={}, followUp={}, mastery={}, questionLen={}, answerLen={}, strategyLen={}, profileLen={}/{}, contextLen={}/{}, imageContextLen={}/{}, evidenceLen={}/{}, evidenceCount={}, retrievedDocs={}, webFallbackUsed={}, assembledContextLen={}",
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
                        packet != null && packet.webFallbackUsed(),
                        safeLength(assembledContext)
                );
            }
            try {
                final String effectiveStrategyHint = normalizedStrategy;
                RoutingChatService.RoutingResult routingResult = callWithRetryResult(
                        () -> generateEvaluationResult(topic, question, userAnswer, difficultyLevel, followUpState, topicMastery, safeProfileSnapshot, assembledContext, finalImageContext, finalEvidence, effectiveStrategyHint),
                        2,
                        "回答评估"
                );
                return new RAGService.EvaluationResult(routingResult.content(), routingResult.inputTokens(), routingResult.outputTokens());
            } catch (RuntimeException e) {
                logger.warn("回答评估失败，返回降级结果。原因: {}", summarizeError(e));
                return new RAGService.EvaluationResult(buildFallbackEvaluation(question, e), 0, 0);
            }
        });
    }

    private String assembleInterviewContext(String topic,
                                            String question,
                                            String userAnswer,
                                            String difficultyLevel,
                                            String followUpState,
                                            double topicMastery,
                                            String profileSnapshot,
                                            String strategyHint,
                                            RAGService.KnowledgePacket packet,
                                            String fallbackContext) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(InterviewContextAttributes.TOPIC, topic == null ? "" : topic);
        attributes.put(InterviewContextAttributes.QUESTION, question == null ? "" : question);
        attributes.put(InterviewContextAttributes.USER_ANSWER, userAnswer == null ? "" : userAnswer);
        attributes.put(InterviewContextAttributes.DIFFICULTY_LEVEL, difficultyLevel == null ? "" : difficultyLevel);
        attributes.put(InterviewContextAttributes.FOLLOW_UP_STATE, followUpState == null ? "" : followUpState);
        attributes.put(InterviewContextAttributes.TOPIC_MASTERY, topicMastery);
        attributes.put(InterviewContextAttributes.PROFILE_SNAPSHOT, profileSnapshot == null ? "" : profileSnapshot);
        attributes.put(InterviewContextAttributes.STRATEGY_HINT, strategyHint == null ? "" : strategyHint);
        if (packet != null) {
            attributes.put(InterviewContextAttributes.KNOWLEDGE_PACKET, packet);
        }
        AgentRuntimeContext runtimeContext = contextAssembler.assemble(AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                question,
                attributes
        ));
        String rendered = runtimeContext.render();
        return rendered.isBlank() ? fallbackContext : rendered;
    }

    private RoutingChatService.RoutingResult generateEvaluationResult(String topic,
                                                                      String question,
                                                                      String userAnswer,
                                                                      String difficultyLevel,
                                                                      String followUpState,
                                                                      double topicMastery,
                                                                      String profileSnapshot,
                                                                      String context,
                                                                      String imageContext,
                                                                      String retrievalEvidence,
                                                                      String strategyHint) {
        String traceId = RAGTraceContext.getTraceId();
        String nodeId = UUID.randomUUID().toString();
        observabilityService.startNode(
                traceId,
                nodeId,
                RAGTraceContext.getCurrentNodeId(),
                TraceNodeDefinitions.LLM_EVALUATION.nodeType(),
                TraceNodeDefinitions.LLM_EVALUATION.nodeName()
        );

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
            RoutingChatService.RoutingResult result = callWithRetryResult(
                    () -> routingChatService.callWithMetadata(pair.systemPrompt(), pair.userPrompt(), ModelRouteType.THINKING, "回答评估"),
                    1,
                    "回答评估"
            );

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

        JsonResult<JsonNode> parseResult = llmJsonParser.parseTree(rawJson, null, null);
        if (!parseResult.success()) {
            return rawJson;
        }
        JsonNode node = parseResult.data();
        if (!(node instanceof ObjectNode objectNode)) {
            return rawJson;
        }
        try {
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

            if (!objectNode.has("nextQuestion") || objectNode.get("nextQuestion").asText().isBlank()) {
                objectNode.put("nextQuestion", "能否进一步结合实际项目场景，谈谈你在使用这项技术时遇到的最大挑战及解决方案？");
            }

            return objectMapper.writeValueAsString(objectNode);
        } catch (Exception e) {
            logger.warn("引用校验失败，返回原始评估结果。原因: {}", summarizeError(e));
            return rawJson;
        }
    }

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

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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

    private String resolveQuestionStrategySummary(String stage,
                                                  String topic,
                                                  String question,
                                                  String difficultyLevel,
                                                  String followUpState,
                                                  String profileSnapshot,
                                                  String resumeContent,
                                                  boolean skipIntro) {
        SkillExecutionResult result = skillOrchestrator.execute(
                "question-strategy",
                new SkillExecutionContext(
                        RAGTraceContext.getTraceId(),
                        "interview-answer-evaluation-service",
                        Map.of(
                                "stage", stage == null ? "" : stage,
                                "topic", topic == null ? "" : topic,
                                "question", question == null ? "" : question,
                                "difficultyLevel", difficultyLevel == null ? "" : difficultyLevel,
                                "followUpState", followUpState == null ? "" : followUpState,
                                "profileSnapshot", profileSnapshot == null ? "" : profileSnapshot,
                                "resumeContent", resumeContent == null ? "" : resumeContent,
                                "skipIntro", skipIntro
                        ),
                        skillOrchestrator.newBudget()
                )
        );
        return result.succeeded() ? result.textOutput("strategySummary") : "";
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

    private String compactMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }

    private String summarizeError(Throwable throwable) {
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

    private String safeSkillText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content.trim();
    }
}
