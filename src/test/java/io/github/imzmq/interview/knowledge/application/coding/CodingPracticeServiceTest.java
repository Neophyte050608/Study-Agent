package io.github.imzmq.interview.knowledge.application.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.AgentSkillService;
import io.github.imzmq.interview.agent.application.context.AgentContextAssembler;
import io.github.imzmq.interview.agent.application.context.AgentContextSourceRegistry;
import io.github.imzmq.interview.agent.application.context.CodingConstraintsContextSource;
import io.github.imzmq.interview.agent.application.context.CodingProfileContextSource;
import io.github.imzmq.interview.agent.application.context.CodingTaskPlanContextSource;
import io.github.imzmq.interview.chat.application.LlmJsonParser;
import io.github.imzmq.interview.chat.application.PromptManager;
import io.github.imzmq.interview.config.skill.SkillExecutionProperties;
import io.github.imzmq.interview.knowledge.application.RAGService;
import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.modelrouting.core.TimeoutHint;
import io.github.imzmq.interview.skill.builtin.CodingInterviewCoachSkill;
import io.github.imzmq.interview.skill.runtime.SkillExecutor;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import io.github.imzmq.interview.skill.runtime.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingPracticeServiceTest {

    private StubRoutingChatService routingChatService;
    private CapturingPromptManager promptManager;
    private CodingPracticeService service;

    @BeforeEach
    void setUp() {
        SkillExecutionProperties skillExecutionProperties = new SkillExecutionProperties();
        SkillOrchestrator skillOrchestrator = new SkillOrchestrator(
                new SkillRegistry(List.of(new CodingInterviewCoachSkill())),
                new SkillExecutor(skillExecutionProperties),
                skillExecutionProperties
        );
        routingChatService = new StubRoutingChatService();
        promptManager = new CapturingPromptManager();
        AgentContextAssembler contextAssembler = new AgentContextAssembler(new AgentContextSourceRegistry(List.of(
                new CodingConstraintsContextSource(),
                new CodingProfileContextSource(),
                new CodingTaskPlanContextSource()
        )));
        service = new CodingPracticeService(
                routingChatService,
                new AgentSkillService("skills"),
                promptManager,
                skillOrchestrator,
                new LlmJsonParser(new ObjectMapper()),
                contextAssembler
        );
    }

    @Test
    void shouldInjectCodingCoachSkillIntoQuestionPrompt() {
        routingChatService.nextResponse = "请完成一道 Two Sum 算法题。";

        service.generateCodingQuestion("数组与字符串", "medium", "高级后端开发", List.of());

        assertEquals("coding-coach", promptManager.lastSystemTemplateName);
        assertEquals("coding-question", promptManager.lastTaskTemplateName);
        String skillBlock = String.valueOf(promptManager.lastVariables.get("skillBlock"));
        assertTrue(skillBlock.contains("Coding Coach"));
        assertTrue(skillBlock.contains("任务: 出题"));
    }


    @Test
    void shouldInjectAssembledContextIntoQuestionPrompt() {
        routingChatService.nextResponse = "请完成一道 Two Sum 算法题。";

        service.generateCodingQuestion("数组与字符串", "medium", "弱项：边界条件", List.of("递归"));

        assertEquals("coding-question", promptManager.lastTaskTemplateName);
        assertEquals("弱项：边界条件", promptManager.lastVariables.get("profileSnapshot"));
        String agentContext = String.valueOf(promptManager.lastVariables.get("agentContext"));
        assertTrue(agentContext.contains("【硬性约束】"));
        assertTrue(agentContext.contains("避免重复主题：递归"));
        assertTrue(agentContext.contains("【用户画像】"));
        assertTrue(agentContext.contains("弱项：边界条件"));
        assertTrue(agentContext.contains("【任务规划】"));
        assertTrue(agentContext.contains("练习主题：数组与字符串"));
    }

    @Test
    void shouldInjectAssembledContextIntoBatchQuizPrompt() {
        routingChatService.nextResponse = "[{\"index\":1,\"stem\":\"题干\",\"options\":[\"A. a\",\"B. b\",\"C. c\",\"D. d\"],\"correctAnswer\":\"A\",\"explanation\":\"解析\"}]";

        service.generateBatchQuiz("Java基础", "easy", 2, "弱项：集合", List.of("线程"));

        assertEquals("batch-quiz-question", promptManager.lastTaskTemplateName);
        assertEquals("弱项：集合", promptManager.lastVariables.get("profileSnapshot"));
        String agentContext = String.valueOf(promptManager.lastVariables.get("agentContext"));
        assertTrue(agentContext.contains("题目数量：2"));
        assertTrue(agentContext.contains("题型：选择题"));
        assertTrue(agentContext.contains("避免重复主题：线程"));
        assertTrue(agentContext.contains("练习主题：Java基础"));
    }

    @Test
    void shouldInjectCodingCoachSkillIntoEvaluationPrompt() {
        routingChatService.nextResponse = "{\"score\":80,\"feedback\":\"ok\",\"nextHint\":\"补复杂度\",\"nextQuestion\":\"再优化一下空间复杂度\"}";

        service.evaluateCodingAnswer("算法", "medium", "Two Sum", "使用 HashMap 一次遍历");

        assertEquals("coding-coach", promptManager.lastSystemTemplateName);
        assertEquals("coding-evaluation", promptManager.lastTaskTemplateName);
        String skillBlock = String.valueOf(promptManager.lastVariables.get("skillBlock"));
        assertTrue(skillBlock.contains("Coding Coach"));
        assertTrue(skillBlock.contains("任务: 评估"));
    }

    @Test
    void shouldReturnFallbackAssessmentForBlankAnswer() {
        RAGService.CodingAssessment assessment = service.evaluateCodingAnswer("算法", "medium", "Two Sum", "   ");

        assertEquals(20, assessment.score());
        assertTrue(assessment.feedback().contains("未提供有效答案"));
        assertTrue(assessment.nextHint().contains("完整思路"));
        assertTrue(assessment.nextQuestion().contains("基础题") || assessment.nextQuestion().contains("数组与字符串") || assessment.nextQuestion().contains("算法"));
    }

    @Test
    void shouldReturnFallbackAssessmentWhenEvaluationJsonCannotBeParsed() {
        routingChatService.nextResponse = "not-json";

        RAGService.CodingAssessment assessment = service.evaluateCodingAnswer("算法", "medium", "Two Sum", "使用 HashMap 一次遍历");

        assertTrue(assessment.score() >= 0 && assessment.score() <= 100);
        assertTrue(assessment.feedback().contains("题解") || assessment.feedback().contains("评估服务暂不可用"));
        assertTrue(assessment.nextQuestion().contains("算法") || assessment.nextQuestion().contains("数组与字符串"));
    }

    private static class StubRoutingChatService extends RoutingChatService {
        private String nextResponse = "";

        private StubRoutingChatService() {
            super(Runnable::run, null, null, null, null, null, null, new NoopChatModel());
        }

        @Override
        public String callWithFirstPacketProbeSupplier(Supplier<String> fallbackSupplier,
                                                       String systemPrompt,
                                                       String userPrompt,
                                                       ModelRouteType routeType,
                                                       TimeoutHint hint,
                                                       String stage) {
            return nextResponse;
        }
    }

    private static class CapturingPromptManager extends PromptManager {
        private String lastSystemTemplateName;
        private String lastTaskTemplateName;
        private Map<String, Object> lastVariables = Map.of();

        private CapturingPromptManager() {
            super(null);
        }

        @Override
        public PromptPair renderSplit(String systemTemplateName, String taskTemplateName, Map<String, Object> variables) {
            this.lastSystemTemplateName = systemTemplateName;
            this.lastTaskTemplateName = taskTemplateName;
            this.lastVariables = variables == null ? Map.of() : new HashMap<>(variables);
            return new PromptPair("system", "user");
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
