package com.example.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningProfileAgentTest {

    @Mock
    private InterviewLearningProfileService interviewLearningProfileService;

    private LearningProfileAgent learningProfileAgent;

    @BeforeEach
    void setUp() {
        when(interviewLearningProfileService.normalizeUserId(anyString())).thenReturn("u1");
        learningProfileAgent = new LearningProfileAgent(new ObjectMapper(), interviewLearningProfileService);
    }

    @Test
    void shouldUpsertEventIdempotently() {
        LearningEvent event = new LearningEvent(
                "evt-1",
                "u1",
                LearningSource.CODING,
                "并发",
                72,
                List.of("边界处理不足"),
                List.of(),
                "第一次提交",
                Instant.now()
        );
        boolean first = learningProfileAgent.upsertEvent(event);
        boolean second = learningProfileAgent.upsertEvent(event);
        assertTrue(first);
        assertFalse(second);
        assertEquals(1, learningProfileAgent.snapshot("u1").totalEvents());
    }

    @Test
    void shouldGenerateDifferentRecommendationsByMode() {
        learningProfileAgent.upsertEvent(new LearningEvent(
                "evt-2",
                "u1",
                LearningSource.INTERVIEW,
                "Java并发",
                58,
                List.of("锁升级理解薄弱"),
                List.of(),
                "报告",
                Instant.now()
        ));
        learningProfileAgent.upsertEvent(new LearningEvent(
                "evt-3",
                "u1",
                LearningSource.CODING,
                "数组",
                88,
                List.of(),
                List.of("复杂度分析清晰"),
                "提交",
                Instant.now()
        ));
        String interviewAdvice = learningProfileAgent.recommend("u1", "interview");
        String codingAdvice = learningProfileAgent.recommend("u1", "coding");
        assertTrue(interviewAdvice.contains("建议面试训练主题"));
        assertTrue(codingAdvice.contains("建议刷题顺序"));
    }
}
