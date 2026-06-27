package io.github.imzmq.interview.knowledge.application.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.AgentSkillService;
import io.github.imzmq.interview.chat.application.LlmJsonParser;
import io.github.imzmq.interview.chat.application.PromptManager;
import io.github.imzmq.interview.chat.application.PromptTemplateService;
import io.github.imzmq.interview.config.observability.ObservabilitySwitchProperties;
import io.github.imzmq.interview.config.skill.SkillExecutionProperties;
import io.github.imzmq.interview.knowledge.application.observability.RAGObservabilityService;
import io.github.imzmq.interview.skill.builtin.QuestionStrategySkill;
import io.github.imzmq.interview.skill.runtime.SkillExecutor;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import io.github.imzmq.interview.skill.runtime.SkillRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import io.github.imzmq.interview.knowledge.application.RAGService;
import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewAnswerEvaluationServiceTest {

    private StubRoutingChatService routingChatService;
    private RAGObservabilityService observabilityService;
    private AgentSkillService agentSkillService;
    private PromptTemplateService promptTemplateService;
    private PromptManager promptManager;
    private ObservabilitySwitchProperties observabilitySwitchProperties;
    private SkillOrchestrator skillOrchestrator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LlmJsonParser llmJsonParser;
    private InterviewAnswerEvaluationService service;

    @BeforeEach
    void setUp() {
        llmJsonParser = new LlmJsonParser(objectMapper);
        observabilitySwitchProperties = new ObservabilitySwitchProperties();
        observabilitySwitchProperties.setRagTraceEnabled(false);
        observabilityService = new RAGObservabilityService(observabilitySwitchProperties, null, null, null);
        agentSkillService = new AgentSkillService("/path/not/exist");
        promptManager = new StaticPromptManager();
        promptTemplateService = new EmptyPromptTemplateService(promptManager);
        SkillExecutionProperties skillExecutionProperties = new SkillExecutionProperties();
        skillOrchestrator = new SkillOrchestrator(
                new SkillRegistry(List.of(new QuestionStrategySkill())),
                new SkillExecutor(skillExecutionProperties),
                skillExecutionProperties
        );
        routingChatService = new StubRoutingChatService();
        service = new InterviewAnswerEvaluationService(
                routingChatService,
                observabilityService,
                agentSkillService,
                promptTemplateService,
                promptManager,
                observabilitySwitchProperties,
                skillOrchestrator,
                llmJsonParser
        );
    }

    @Test
    void shouldFilterInvalidEvidenceReferencesAndFillNextQuestion() throws Exception {
        routingChatService.nextResult = new RoutingChatService.RoutingResult("""
                        {"score":85,"accuracy":84,"logic":83,"depth":82,"boundary":81,
                        "deductions":["边界解释不足"],
                        "citations":["99. [fake]","1. [ok]"],
                        "conflicts":["异常结论~7","隔离级别描述不全~1"],
                        "feedback":"回答较好"}
                        """, 11, 7, 0L);

        RAGService.KnowledgePacket packet = new RAGService.KnowledgePacket(
                "事务隔离级别",
                List.of(),
                "事务上下文",
                "1. [obsidian:tx.md] tags=技术栈 | 事务隔离级别包括读未提交、读已提交、可重复读和串行化。\n" +
                        "2. [obsidian:lock.md] tags=技术栈 | 锁机制用于并发控制。",
                false
        );

        String result = service.evaluateAndValidate("Spring", "什么是事务隔离级别", "回答", "INTERMEDIATE", "PROBE", 72.0, "画像", "", packet);
        JsonNode node = objectMapper.readTree(result);

        assertEquals(1, node.path("citations").size());
        assertEquals("1. [ok]", node.path("citations").get(0).asText());
        assertEquals(1, node.path("conflicts").size());
        assertEquals("隔离级别描述不全~1", node.path("conflicts").get(0).asText());
        assertTrue(node.path("deductions").toString().contains("自动过滤"));
        assertTrue(node.hasNonNull("nextQuestion"));
    }

    @Test
    void shouldReturnFallbackEvaluationWhenModelFails() throws Exception {
        routingChatService.nextFailure = new ResourceAccessException("timeout");

        RAGService.EvaluationResult result = service.evaluateWithKnowledge(
                "Java",
                "什么是 JVM 内存模型",
                "我的回答",
                "INTERMEDIATE",
                "PROBE",
                70.0,
                "画像",
                "",
                new RAGService.KnowledgePacket("query", List.of(), "", "[]", true)
        );

        JsonNode node = objectMapper.readTree(result.json());
        assertEquals(0, node.path("score").asInt());
        assertEquals(0, result.inputTokens());
        assertEquals(0, result.outputTokens());
        assertTrue(node.path("feedback").asText().contains("超时") || node.path("feedback").asText().contains("不可用"));
    }


    private static class StubRoutingChatService extends RoutingChatService {
        private RoutingResult nextResult;
        private RuntimeException nextFailure;

        private StubRoutingChatService() {
            super(Runnable::run, null, null, null, null, null, null, new NoopChatModel());
        }

        @Override
        public RoutingResult callWithMetadata(String systemPrompt, String userPrompt, ModelRouteType routeType, String stage) {
            if (nextFailure != null) {
                throw nextFailure;
            }
            return nextResult;
        }
    }

    private static class StaticPromptManager extends PromptManager {
        private StaticPromptManager() {
            super(null);
        }

        @Override
        public PromptPair renderSplit(String systemTemplateName, String taskTemplateName, Map<String, Object> variables) {
            return new PromptPair("system", "user");
        }
    }

    private static class EmptyPromptTemplateService extends PromptTemplateService {
        private EmptyPromptTemplateService(PromptManager promptManager) {
            super(null, promptManager, null);
        }

        @Override
        public List<Map<String, Object>> loadFewShotCases(String path) {
            return List.of();
        }
    }

    private static class NoopChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(""), ChatGenerationMetadata.NULL)), ChatResponseMetadata.builder().build());
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
