package com.example.interview.agent;

import com.example.interview.service.RAGService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvaluationAgentTest {

    @Test
    void shouldParseCitationsAndConflictsFromJson() {
        RAGService ragService = mock(RAGService.class);
        when(ragService.processAnswer("Java并发", "什么是可见性", "回答", "BASIC", "PROBE", 66.0, "画像")).thenReturn("""
                {"score":82,"accuracy":80,"logic":81,"depth":79,"boundary":78,
                "deductions":["边界条件不完整"],
                "citations":["1. [note.md]","2. [design.md]"],
                "conflicts":["volatile 语义不完整｜1"],
                "feedback":"回答整体不错","nextQuestion":"请继续说明 happens-before"}
                """);
        EvaluationAgent agent = new EvaluationAgent(ragService, new ObjectMapper());

        EvaluationAgent.EvaluationResult result = agent.evaluateAnswer("Java并发", "什么是可见性", "回答", "BASIC", "PROBE", 66.0, "画像");

        assertEquals(82, result.score());
        assertEquals(2, result.citations().size());
        assertEquals(1, result.conflicts().size());
        assertTrue(result.feedback().contains("维度评分"));
    }

    @Test
    void shouldFallbackToTagParsingWhenJsonMalformed() {
        RAGService ragService = mock(RAGService.class);
        when(ragService.processAnswer("JVM", "什么是GC", "回答", "BASIC", "PROBE", 60.0, "画像")).thenReturn("""
                <score>70</score>
                <accuracy>71</accuracy>
                <logic>72</logic>
                <depth>73</depth>
                <boundary>74</boundary>
                <deductions>- 术语不精确</deductions>
                <feedback>可读性尚可</feedback>
                <next_question>请讲讲CMS和G1的差异</next_question>
                """);
        EvaluationAgent agent = new EvaluationAgent(ragService, new ObjectMapper());

        EvaluationAgent.EvaluationResult result = agent.evaluateAnswer("JVM", "什么是GC", "回答", "BASIC", "PROBE", 60.0, "画像");

        assertEquals(70, result.score());
        assertEquals("请讲讲CMS和G1的差异", result.nextQuestion());
        assertTrue(result.citations().isEmpty());
    }

    @Test
    void shouldEvaluateWithKnowledgePacket() {
        RAGService ragService = mock(RAGService.class);
        RAGService.KnowledgePacket packet = new RAGService.KnowledgePacket("并发 可见性", java.util.List.of(), "上下文", "1. [note.md]", false);
        when(ragService.evaluateWithKnowledge("Java并发", "什么是可见性", "回答", "INTERMEDIATE", "ADVANCE", 82.0, "画像", "优先场景化深挖", packet)).thenReturn(new RAGService.EvaluationResult("""
                {"score":90,"accuracy":89,"logic":88,"depth":87,"boundary":86,
                "deductions":["术语还可更精准"],
                "citations":["1. [note.md]"],
                "conflicts":[],
                "feedback":"回答扎实","nextQuestion":"请谈可见性与有序性"}
                """, 100, 50));
        EvaluationAgent agent = new EvaluationAgent(ragService, new ObjectMapper());

        EvaluationAgent.LayeredEvaluation result = agent.evaluateAnswerWithKnowledge("Java并发", "什么是可见性", "回答", "INTERMEDIATE", "ADVANCE", 82.0, "画像", "优先场景化深挖", packet);

        assertEquals(90, result.result().score());
        assertEquals(1, result.result().citations().size());
        assertEquals("并发 可见性", result.trace().retrievalQuery());
        assertTrue(result.result().feedback().contains("维度评分"));
    }
}
