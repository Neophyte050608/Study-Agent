package com.example.interview.im.service;

import com.example.interview.agent.TaskRouterAgent;
import com.example.interview.agent.task.TaskType;
import com.example.interview.config.IntentTreeProperties;
import com.example.interview.service.IntentTreeRoutingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImWebhookServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private ImConversationStore conversationStore;
    @Mock
    private TaskRouterAgent taskRouterAgent;
    @Mock
    private FeishuReplyAdapter feishuReplyAdapter;
    @Mock
    private QqReplyAdapter qqReplyAdapter;
    @Mock
    private IntentTreeRoutingService intentTreeRoutingService;

    private ImWebhookService newService() {
        return new ImWebhookService(
                redisTemplate, conversationStore, taskRouterAgent, feishuReplyAdapter, qqReplyAdapter, new IntentTreeProperties(), intentTreeRoutingService
        );
    }

    @Test
    void shouldResolveClarificationByIndex() {
        ImWebhookService service = newService();
        String state = "{\"options\":[{\"label\":\"刷题\",\"taskType\":\"CODING_PRACTICE\"},{\"label\":\"面试\",\"taskType\":\"INTERVIEW_START\"}]}";
        TaskType resolved = service.resolveClarificationTaskType(state, "2");
        assertEquals(TaskType.INTERVIEW_START, resolved);
    }

    @Test
    void shouldResolveClarificationByLabel() {
        ImWebhookService service = newService();
        String state = "{\"options\":[{\"label\":\"刷题\",\"taskType\":\"CODING_PRACTICE\"},{\"label\":\"面试\",\"taskType\":\"INTERVIEW_START\"}]}";
        TaskType resolved = service.resolveClarificationTaskType(state, "我选刷题");
        assertEquals(TaskType.CODING_PRACTICE, resolved);
    }

    @Test
    void shouldBuildCodingPayloadFromClarificationReply() {
        ImWebhookService service = newService();
        when(intentTreeRoutingService.refineSlots("CODING_PRACTICE", "我想练习 1 来3道Java选择题 medium", ""))
                .thenReturn(Map.of("mode", "", "topic", "Java"));
        String state = "{\"options\":[{\"label\":\"刷题\",\"taskType\":\"CODING_PRACTICE\"}],\"originalQuery\":\"我想练习\"}";
        Map<String, Object> payload = service.buildClarifiedPayload(TaskType.CODING_PRACTICE, "1 来3道Java选择题 medium", state);
        assertTrue(Boolean.TRUE.equals(payload.get("clarificationResolved")));
        assertEquals("CHOICE", payload.get("questionType"));
        assertEquals("选择题", payload.get("type"));
        assertEquals(3, payload.get("count"));
        assertEquals("Java", payload.get("topic"));
        assertEquals("medium", payload.get("difficulty"));
        assertEquals("我想练习 1 来3道Java选择题 medium", payload.get("query"));
    }

    @Test
    void shouldMergeRefinedSlotsWhenRuleParsingMissesFields() {
        ImWebhookService service = newService();
        when(intentTreeRoutingService.refineSlots("CODING_PRACTICE", "我想练习 2 并发 easy", ""))
                .thenReturn(Map.of("questionType", "ALGORITHM", "type", "算法题", "topic", "并发", "difficulty", "easy", "count", 2));
        String state = "{\"options\":[{\"label\":\"刷题\",\"taskType\":\"CODING_PRACTICE\"}],\"originalQuery\":\"我想练习\"}";
        Map<String, Object> payload = service.buildClarifiedPayload(TaskType.CODING_PRACTICE, "2 并发 easy", state);
        assertEquals("ALGORITHM", payload.get("questionType"));
        assertEquals("算法题", payload.get("type"));
        assertEquals("并发", payload.get("topic"));
        assertEquals("easy", payload.get("difficulty"));
        assertEquals(2, payload.get("count"));
    }

    @Test
    void shouldAcquireIdempotencyLockAtomically() {
        ImWebhookService service = newService();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent("im:event:idempotency:event-1", "1", 1, java.util.concurrent.TimeUnit.HOURS))
                .thenReturn(true);
        assertTrue(service.tryRecordEvent("event-1"));
    }

    @Test
    void shouldRejectDuplicatedEventWhenIdempotencyLockExists() {
        ImWebhookService service = newService();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent("im:event:idempotency:event-1", "1", 1, java.util.concurrent.TimeUnit.HOURS))
                .thenReturn(false);
        assertEquals(false, service.tryRecordEvent("event-1"));
    }
}
