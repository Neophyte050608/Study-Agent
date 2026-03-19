package com.example.interview.controller;

import com.example.interview.agent.DecisionLayerAgent;
import com.example.interview.agent.EvaluationAgent;
import com.example.interview.agent.EvaluationLayerAgent;
import com.example.interview.agent.GrowthLayerAgent;
import com.example.interview.agent.InterviewOrchestratorAgent;
import com.example.interview.agent.KnowledgeLayerAgent;
import com.example.interview.rag.ResumeLoader;
import com.example.interview.service.IngestionService;
import com.example.interview.service.InterviewLearningProfileService;
import com.example.interview.service.InterviewService;
import com.example.interview.service.LexicalIndexService;
import com.example.interview.service.RAGObservabilityService;
import com.example.interview.service.RAGService;
import com.example.interview.service.RetrievalEvaluationService;
import com.example.interview.service.UserIdentityResolver;
import com.example.interview.session.InMemorySessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.ai.vectorstore.VectorStore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InterviewController.class)
@Import({InterviewService.class, InterviewOrchestratorAgent.class, EvaluationAgent.class, KnowledgeLayerAgent.class, DecisionLayerAgent.class, EvaluationLayerAgent.class, GrowthLayerAgent.class, InMemorySessionRepository.class, InterviewLearningProfileService.class, UserIdentityResolver.class, RAGObservabilityService.class, RetrievalEvaluationService.class})
class InterviewControllerFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
}
