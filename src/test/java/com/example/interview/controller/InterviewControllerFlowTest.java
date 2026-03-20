package com.example.interview.controller;

import com.example.interview.agent.DecisionLayerAgent;
import com.example.interview.agent.EvaluationAgent;
import com.example.interview.agent.EvaluationLayerAgent;
import com.example.interview.agent.GrowthLayerAgent;
import com.example.interview.agent.InterviewOrchestratorAgent;
import com.example.interview.agent.KnowledgeLayerAgent;
import com.example.interview.agent.CodingPracticeAgent;
import com.example.interview.agent.NoteMakingAgent;
import com.example.interview.agent.TaskRouterAgent;
import com.example.interview.agent.a2a.InMemoryA2ABus;
import com.example.interview.agent.a2a.A2AIdempotencyStore;
import com.example.interview.config.A2ABusConfig;
import com.example.interview.config.SecurityConfig;
import com.example.interview.rag.ResumeLoader;
import com.example.interview.service.IngestionService;
import com.example.interview.service.AgentEvaluationService;
import com.example.interview.service.InterviewLearningProfileService;
import com.example.interview.service.LearningProfileAgent;
import com.example.interview.service.McpGatewayService;
import com.example.interview.service.InterviewService;
import com.example.interview.service.LexicalIndexService;
import com.example.interview.service.OpsAuditService;
import com.example.interview.service.RAGObservabilityService;
import com.example.interview.service.RAGService;
import com.example.interview.service.RetrievalEvaluationService;
import com.example.interview.service.UserIdentityResolver;
import com.example.interview.session.InMemorySessionRepository;
import com.example.interview.tool.StubMcpCapabilityGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = InterviewController.class, properties = {"app.a2a.bus.type=inmemory"})
@Import({InterviewService.class, TaskRouterAgent.class, InterviewOrchestratorAgent.class, CodingPracticeAgent.class, NoteMakingAgent.class, EvaluationAgent.class, KnowledgeLayerAgent.class, DecisionLayerAgent.class, EvaluationLayerAgent.class, GrowthLayerAgent.class, InMemorySessionRepository.class, InterviewLearningProfileService.class, LearningProfileAgent.class, McpGatewayService.class, OpsAuditService.class, UserIdentityResolver.class, RAGObservabilityService.class, RetrievalEvaluationService.class, AgentEvaluationService.class, A2AIdempotencyStore.class, InMemoryA2ABus.class, A2ABusConfig.class, SecurityConfig.class, StubMcpCapabilityGateway.class})
class InterviewControllerFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OpsAuditService opsAuditService;

    @MockBean
    private IngestionService ingestionService;

    @MockBean
    private RAGService ragService;

    @MockBean
    private ResumeLoader resumeLoader;

    @MockBean
    private VectorStore vectorStore;

    @MockBean
    private LexicalIndexService lexicalIndexService;

    @Test
    void shouldCompleteInterviewFlow() throws Exception {
        when(ragService.generateFirstQuestion(anyString(), anyString(), anyString())).thenReturn("什么是线程安全？");
        when(ragService.buildKnowledgePacket(anyString(), anyString())).thenReturn(new RAGService.KnowledgePacket("线程安全", java.util.List.of(), "上下文", "1. [note.md] tags=技术栈 | 线程安全定义", false));
        when(ragService.evaluateWithKnowledge(anyString(), anyString(), anyString(), anyString(), anyString(), anyDouble(), anyString(), anyString(), any())).thenReturn("""
                {"score":88,"accuracy":86,"logic":85,"depth":84,"boundary":83,
                "deductions":["边界条件不完整"],"citations":["1. [note.md]"],"conflicts":["可见性描述不完整｜1"],
                "feedback":"结构清晰，覆盖了关键点。","nextQuestion":"请说明 synchronized 与 Lock 的区别。"}
                """);
        when(ragService.generateFinalReport(anyString(), any(), anyString())).thenReturn("""
                <summary>整体表现良好。</summary>
                <incomplete>暂无明显不完整回答。</incomplete>
                <weak>暂无明显薄弱点。</weak>
                <wrong>暂无明确错误结论。</wrong>
                <obsidian_updates>补充锁升级过程与典型场景。</obsidian_updates>
                <next_focus>继续深挖并发可见性与有序性。</next_focus>
                """);

        String startPayload = """
                {
                  "topic": "Java并发",
                  "resumePath": "",
                  "totalQuestions": 1
                }
                """;

        MvcResult startResult = mockMvc.perform(post("/api/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.currentQuestion").value("什么是线程安全？"))
                .andReturn();

        JsonNode startNode = objectMapper.readTree(startResult.getResponse().getContentAsString());
        String sessionId = startNode.get("id").asText();

        String answerPayload = """
                {
                  "sessionId": "%s",
                  "answer": "多个线程访问共享数据时，结果保持正确且一致。"
                }
                """.formatted(sessionId);

        mockMvc.perform(post("/api/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answerPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(88))
                .andExpect(jsonPath("$.finished").value(true))
                .andExpect(jsonPath("$.answeredCount").value(1))
                .andExpect(jsonPath("$.totalQuestions").value(1))
                .andExpect(jsonPath("$.citations[0]").value("1. [note.md]"))
                .andExpect(jsonPath("$.conflicts[0]").value("可见性描述不完整｜1"));

        String reportPayload = """
                {
                  "sessionId": "%s"
                }
                """.formatted(sessionId);

        mockMvc.perform(post("/api/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("整体表现良好。"))
                .andExpect(jsonPath("$.obsidianUpdates").value("补充锁升级过程与典型场景。"))
                .andExpect(jsonPath("$.averageScore").value(88.0))
                .andExpect(jsonPath("$.answeredCount").value(1));
    }

    @Test
    void shouldDispatchLearningPlanTask() throws Exception {
        String payload = """
                {
                  "taskType": "LEARNING_PLAN",
                  "payload": {"topic": "并发"},
                  "context": {"source": "test"}
                }
                """;
        mockMvc.perform(post("/api/task/dispatch")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.agent").value("NoteMakingAgent"))
                .andExpect(jsonPath("$.data.status").value("generated"))
                .andExpect(jsonPath("$.data.notePath").isString());
    }

    @Test
    void shouldDispatchCodingPracticeStartAndSubmit() throws Exception {
        String startPayload = """
                {
                  "taskType": "CODING_PRACTICE",
                  "payload": {"action": "start", "topic": "哈希表", "difficulty": "easy"},
                  "context": {"source": "test"}
                }
                """;
        MvcResult startResult = mockMvc.perform(post("/api/task/dispatch")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .header("X-User-Id", "tester-flow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.agent").value("CodingPracticeAgent"))
                .andExpect(jsonPath("$.data.status").value("started"))
                .andReturn();
        JsonNode startNode = objectMapper.readTree(startResult.getResponse().getContentAsString());
        String sessionId = startNode.path("data").path("sessionId").asText("");

        String submitPayload = """
                {
                  "taskType": "CODING_PRACTICE",
                  "payload": {
                    "action": "submit",
                    "sessionId": "%s",
                    "answer": "先用 for 遍历并用 HashMap 记录位置，复杂度 O(n)，注意空数组边界。"
                  },
                  "context": {"source": "test"}
                }
                """.formatted(sessionId);
        mockMvc.perform(post("/api/task/dispatch")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .header("X-User-Id", "tester-flow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.agent").value("CodingPracticeAgent"))
                .andExpect(jsonPath("$.data.status").value("evaluated"))
                .andExpect(jsonPath("$.data.score").isNumber())
                .andExpect(jsonPath("$.data.nextQuestion").isString());

        String nextStartPayload = """
                {
                  "taskType": "CODING_PRACTICE",
                  "payload": {"action": "start", "difficulty": "easy"},
                  "context": {"source": "test"}
                }
                """;
        mockMvc.perform(post("/api/task/dispatch")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .header("X-User-Id", "tester-flow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nextStartPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.topicFromProfile").value(true))
                .andExpect(jsonPath("$.data.profileSnapshotApplied").value(true))
                .andExpect(jsonPath("$.data.topic").isString());

        mockMvc.perform(get("/api/observability/profile/events")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .header("X-User-Id", "tester-flow")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray());
    }

    @Test
    void shouldReturnProfileOverviewAndRecommendations() throws Exception {
        mockMvc.perform(get("/api/profile/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot").exists())
                .andExpect(jsonPath("$.recommendInterview").isString())
                .andExpect(jsonPath("$.recommendCoding").isString());

        mockMvc.perform(get("/api/profile/recommendations")
                        .param("mode", "coding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("coding"))
                .andExpect(jsonPath("$.recommendation").isString());
    }

    @Test
    void shouldDiscoverAndInvokeMcpCapability() throws Exception {
        String traceId = "trace-mcp-001";
        mockMvc.perform(get("/api/mcp/capabilities")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .param("traceId", traceId)
                        .header("X-User-Id", "mcp-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(jsonPath("$.capabilities").isArray());

        String payload = """
                {
                  "capability": "obsidian.write",
                  "params": {
                    "topic": "Java并发",
                    "content": "test-plan"
                  },
                  "context": {
                    "source": "test",
                    "traceId": "trace-mcp-001"
                  }
                }
                """;
        mockMvc.perform(post("/api/mcp/invoke")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .header("X-User-Id", "mcp-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").isString())
                .andExpect(jsonPath("$.capability").value("obsidian.write"))
                .andExpect(jsonPath("$.result.notePath").isString());

        mockMvc.perform(get("/api/observability/mcp/logs")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .param("traceId", traceId)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray());
    }

    @Test
    void shouldFilterMcpLogsByErrorCode() throws Exception {
        opsAuditService.record(
                "tester",
                "MCP_INVOKE",
                Map.of("capability", "obsidian.write", "errorCode", "MCP_PERMISSION_DENIED", "retryable", false),
                false,
                "permission denied",
                "trace-err-1"
        );
        opsAuditService.record(
                "tester",
                "MCP_INVOKE",
                Map.of("capability", "obsidian.write", "errorCode", "MCP_UNREACHABLE", "retryable", true),
                false,
                "bridge down",
                "trace-err-1"
        );

        mockMvc.perform(get("/api/observability/mcp/logs")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .param("traceId", "trace-err-1")
                        .param("errorCode", "MCP_PERMISSION_DENIED")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("MCP_PERMISSION_DENIED"))
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].payload.errorCode").value("MCP_PERMISSION_DENIED"));
    }

    @Test
    void shouldFilterMcpLogsByTimeoutErrorCode() throws Exception {
        opsAuditService.record(
                "tester",
                "MCP_INVOKE",
                Map.of("capability", "obsidian.write", "errorCode", "MCP_TIMEOUT", "retryable", true),
                false,
                "timeout",
                "trace-timeout-1"
        );
        opsAuditService.record(
                "tester",
                "MCP_INVOKE",
                Map.of("capability", "obsidian.write", "errorCode", "MCP_INVALID_RESPONSE", "retryable", false),
                false,
                "invalid response",
                "trace-timeout-1"
        );

        mockMvc.perform(get("/api/observability/mcp/logs")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .param("traceId", "trace-timeout-1")
                        .param("errorCode", "MCP_TIMEOUT")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("MCP_TIMEOUT"))
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].payload.errorCode").value("MCP_TIMEOUT"))
                .andExpect(jsonPath("$.records[0].payload.retryable").value(true));
    }

    @Test
    void shouldFilterMcpLogsByInvalidResponseErrorCode() throws Exception {
        opsAuditService.record(
                "tester",
                "MCP_INVOKE",
                Map.of("capability", "obsidian.write", "errorCode", "MCP_INVALID_RESPONSE", "retryable", false),
                false,
                "invalid response",
                "trace-invalid-1"
        );
        opsAuditService.record(
                "tester",
                "MCP_INVOKE",
                Map.of("capability", "obsidian.write", "errorCode", "MCP_UNREACHABLE", "retryable", true),
                false,
                "unreachable",
                "trace-invalid-1"
        );

        mockMvc.perform(get("/api/observability/mcp/logs")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .param("traceId", "trace-invalid-1")
                        .param("errorCode", "MCP_INVALID_RESPONSE")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("MCP_INVALID_RESPONSE"))
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].payload.errorCode").value("MCP_INVALID_RESPONSE"))
                .andExpect(jsonPath("$.records[0].payload.retryable").value(false));
    }

    @Test
    void shouldRunAgentEvaluationTrialAndQueryTranscript() throws Exception {
        String payload = """
                {
                  "agent": "NoteMakingAgent",
                  "task": "根据输入生成学习计划",
                  "expected": "学习计划 Java 并发",
                  "input": {"topic": "Java并发", "level": "mid"}
                }
                """;
        MvcResult trialResult = mockMvc.perform(post("/api/observability/agent-eval/trials")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS_ADMIN")))
                        .header("X-User-Id", "eval-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trialId").isString())
                .andExpect(jsonPath("$.agent").value("NoteMakingAgent"))
                .andExpect(jsonPath("$.score.overall").isNumber())
                .andExpect(jsonPath("$.score.scorerType").isString())
                .andReturn();

        String trialId = objectMapper.readTree(trialResult.getResponse().getContentAsString()).path("trialId").asText();

        mockMvc.perform(get("/api/observability/agent-eval/trials/" + trialId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trialId").value(trialId))
                .andExpect(jsonPath("$.transcript").isArray())
                .andExpect(jsonPath("$.turnCount").value(3));

        mockMvc.perform(get("/api/observability/agent-eval/transcripts/" + trialId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trialId").value(trialId))
                .andExpect(jsonPath("$.turnCount").value(3))
                .andExpect(jsonPath("$.turns").isArray());
    }

    @Test
    void shouldListAgentEvaluationScorers() throws Exception {
        mockMvc.perform(get("/api/observability/agent-eval/scorers")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray())
                .andExpect(jsonPath("$.records.length()").value(4))
                .andExpect(jsonPath("$.records[0].name").isString());
    }

    @Test
    void shouldRunAgentTrialInRealModeWithOptimization() throws Exception {
        String payload = """
                {
                  "agent": "NoteMakingAgent",
                  "taskType": "LEARNING_PLAN",
                  "executionMode": "real",
                  "optimize": true,
                  "maxIterations": 3,
                  "passThreshold": 92,
                  "task": "输出可执行学习计划",
                  "expected": "学习计划 Java 并发 线程池 锁",
                  "input": {"topic": "Java并发", "phase": "phase-2"},
                  "context": {"traceId": "eval-real-001"},
                  "scorerType": "hybrid"
                }
                """;

        mockMvc.perform(post("/api/observability/agent-eval/trials")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS_ADMIN")))
                        .header("X-User-Id", "eval-real-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionMode").value("real"))
                .andExpect(jsonPath("$.taskType").value("LEARNING_PLAN"))
                .andExpect(jsonPath("$.iterations").isNumber())
                .andExpect(jsonPath("$.evaluationHistory").isArray())
                .andExpect(jsonPath("$.score.scorerType").value("hybrid"))
                .andExpect(jsonPath("$.transcript.length()").value(3))
                .andExpect(jsonPath("$.transcript[2].content").isString());
    }

    @Test
    void shouldReturnA2AIdempotencySnapshot() throws Exception {
        mockMvc.perform(get("/api/observability/a2a/idempotency")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttlMillis").isNumber())
                .andExpect(jsonPath("$.maxSize").isNumber())
                .andExpect(jsonPath("$.redisKeyPrefix").isString());
    }

    @Test
    void shouldRejectDlqReplayWhenBusIsNotRocketMq() throws Exception {
        String payload = """
                {
                  "message": "{\\"messageId\\":\\"m1\\"}"
                }
                """;
        mockMvc.perform(post("/api/observability/a2a/dlq/replay")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("当前非 rocketmq 总线，无法重放 DLQ"));
    }

    @Test
    void shouldRejectDlqReplayBatchWhenBusIsNotRocketMq() throws Exception {
        String payload = """
                {
                  "messages": ["{\\"messageId\\":\\"m1\\"}", "{\\"messageId\\":\\"m2\\"}"]
                }
                """;
        mockMvc.perform(post("/api/observability/a2a/dlq/replay/batch")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("当前非 rocketmq 总线，无法重放 DLQ"));
    }

    @Test
    void shouldClearIdempotencyKeys() throws Exception {
        String payload = """
                {
                  "scope": "memory",
                  "keyContains": "task-routing"
                }
                """;
        mockMvc.perform(post("/api/observability/a2a/idempotency/clear")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("memory"))
                .andExpect(jsonPath("$.clearedMemory").isNumber())
                .andExpect(jsonPath("$.inMemorySize").isNumber());
    }

    @Test
    void shouldRejectInvalidIdempotencyScope() throws Exception {
        String payload = """
                {
                  "scope": "invalid"
                }
                """;
        mockMvc.perform(post("/api/observability/a2a/idempotency/clear")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("scope 仅支持 memory|redis|all"));
    }

    @Test
    void shouldReturnOpsAuditRecords() throws Exception {
        String payload = """
                {
                  "scope": "memory",
                  "keyContains": "task-routing"
                }
                """;
        mockMvc.perform(post("/api/observability/a2a/idempotency/clear")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/observability/audit/ops")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray());
    }
}
