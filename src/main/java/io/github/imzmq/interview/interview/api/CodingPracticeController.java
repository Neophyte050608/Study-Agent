package io.github.imzmq.interview.interview.api;

import io.github.imzmq.interview.agent.runtime.CodingPracticeAgent;
import io.github.imzmq.interview.chat.application.contract.ChatMessageOperations;
import io.github.imzmq.interview.chat.application.contract.result.ChatMessageView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 编程练习控制器。
 */
@RestController
@RequestMapping("/api/coding")
public class CodingPracticeController {

    private final CodingPracticeAgent codingPracticeAgent;
    private final ChatMessageOperations chatMessageOperations;
    private final ObjectMapper objectMapper;

    public CodingPracticeController(CodingPracticeAgent codingPracticeAgent,
                                    ChatMessageOperations chatMessageOperations,
                                    ObjectMapper objectMapper) {
        this.codingPracticeAgent = codingPracticeAgent;
        this.chatMessageOperations = chatMessageOperations;
        this.objectMapper = objectMapper;
    }

    /**
     * 批量提交选择题结果。
     */
    @PostMapping("/batch-quiz/submit")
    public ResponseEntity<Map<String, Object>> submitBatchQuiz(@RequestBody Map<String, Object> body) {
        body.put("action", "batch-quiz-submit");
        return ResponseEntity.ok(codingPracticeAgent.execute(body));
    }

    @PostMapping("/scenario-card/submit")
    public ResponseEntity<Map<String, Object>> submitScenarioCard(@RequestBody Map<String, Object> body) {
        body.put("action", "submit-scenario-card");
        Map<String, Object> result = codingPracticeAgent.execute(body);
        if (!"scenario_evaluated".equals(result.get("status"))) {
            return ResponseEntity.ok(result);
        }

        String messageId = text(body, "messageId");
        String chatSessionId = text(body, "chatSessionId");
        String codingSessionId = text(body, "sessionId");
        String cardId = text(body, "cardId");
        Object scenarioPayload = result.get("scenarioPayload");
        ChatMessageView existing = validateCardMessage(messageId, chatSessionId, codingSessionId, cardId);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "conflict",
                    "message", "场景题卡片与当前会话状态不一致，请刷新后重试"
            ));
        }
        chatMessageOperations.updateAssistantMessage(
                messageId,
                toJson(scenarioPayload),
                buildCardMetadata(existing, body, scenarioPayload, "coding_practice_scenario", "coding-scenario-card", "scenario-card"),
                "scenario_card"
        );
        return ResponseEntity.ok(result);
    }

    @PostMapping("/fill-card/submit")
    public ResponseEntity<Map<String, Object>> submitFillCard(@RequestBody Map<String, Object> body) {
        body.put("action", "submit-fill-card");
        Map<String, Object> result = codingPracticeAgent.execute(body);
        if (!"fill_evaluated".equals(result.get("status"))) {
            return ResponseEntity.ok(result);
        }

        String messageId = text(body, "messageId");
        String chatSessionId = text(body, "chatSessionId");
        String codingSessionId = text(body, "sessionId");
        String cardId = text(body, "cardId");
        Object fillPayload = result.get("fillPayload");
        ChatMessageView existing = validateCardMessage(messageId, chatSessionId, codingSessionId, cardId);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "conflict",
                    "message", "填空题卡片与当前会话状态不一致，请刷新后重试"
            ));
        }
        chatMessageOperations.updateAssistantMessage(
                messageId,
                toJson(fillPayload),
                buildCardMetadata(existing, body, fillPayload, "coding_practice_fill", "coding-fill-card", "fill-card"),
                "fill_card"
        );
        return ResponseEntity.ok(result);
    }

    @PostMapping("/scenario-card/next")
    public ResponseEntity<Map<String, Object>> nextScenarioCard(@RequestBody Map<String, Object> body) {
        String currentMessageId = text(body, "messageId");
        String chatSessionId = text(body, "chatSessionId");
        String codingSessionId = text(body, "sessionId");
        String cardId = text(body, "cardId");
        ChatMessageView existing = validateCardMessage(currentMessageId, chatSessionId, codingSessionId, cardId);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "conflict",
                    "message", "场景题卡片与当前会话状态不一致，请刷新后重试"
            ));
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("action", "next-scenario-card");
        request.put("sessionId", codingSessionId);
        request.put("userId", text(body, "userId"));
        request.put("cardId", cardId);
        Map<String, Object> result = codingPracticeAgent.execute(request);

        if ("scenario_question_generated".equals(result.get("status"))) {
            Object currentPayload = parseCardPayload(existing);
            if (currentPayload != null) {
                Map<String, Object> currentPayloadMap = objectMapper.convertValue(currentPayload, Map.class);
                currentPayloadMap.put("canContinue", false);
                chatMessageOperations.updateAssistantMessage(
                        currentMessageId,
                        toJson(currentPayloadMap),
                        buildCardMetadata(existing, body, currentPayloadMap, "coding_practice_scenario", "coding-scenario-card", "scenario-card"),
                        "scenario_card"
                );
            }
            Object scenarioPayload = result.get("scenarioPayload");
            if (!chatSessionId.isBlank() && scenarioPayload != null) {
                chatMessageOperations.saveAssistantMessage(
                        chatSessionId,
                        toJson(scenarioPayload),
                        buildCardMetadata(null, body, scenarioPayload, "coding_practice_scenario", "coding-scenario-card", "scenario-card"),
                        "scenario_card"
                );
            }
        } else if ("completed".equals(result.get("status"))) {
            if (!chatSessionId.isBlank()) {
                chatMessageOperations.saveAssistantMessage(
                        chatSessionId,
                        String.valueOf(result.getOrDefault("message", "本次刷题已全部完成！")),
                        Map.of("type", "coding_practice"),
                        "text"
                );
            }
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/fill-card/next")
    public ResponseEntity<Map<String, Object>> nextFillCard(@RequestBody Map<String, Object> body) {
        String currentMessageId = text(body, "messageId");
        String chatSessionId = text(body, "chatSessionId");
        String codingSessionId = text(body, "sessionId");
        String cardId = text(body, "cardId");
        ChatMessageView existing = validateCardMessage(currentMessageId, chatSessionId, codingSessionId, cardId);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "conflict",
                    "message", "填空题卡片与当前会话状态不一致，请刷新后重试"
            ));
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("action", "next-fill-card");
        request.put("sessionId", codingSessionId);
        request.put("userId", text(body, "userId"));
        request.put("cardId", cardId);
        Map<String, Object> result = codingPracticeAgent.execute(request);

        if ("fill_question_generated".equals(result.get("status"))) {
            Object currentPayload = parseCardPayload(existing);
            if (currentPayload != null) {
                Map<String, Object> currentPayloadMap = objectMapper.convertValue(currentPayload, Map.class);
                currentPayloadMap.put("canContinue", false);
                chatMessageOperations.updateAssistantMessage(
                        currentMessageId,
                        toJson(currentPayloadMap),
                        buildCardMetadata(existing, body, currentPayloadMap, "coding_practice_fill", "coding-fill-card", "fill-card"),
                        "fill_card"
                );
            }
            Object fillPayload = result.get("fillPayload");
            if (!chatSessionId.isBlank() && fillPayload != null) {
                chatMessageOperations.saveAssistantMessage(
                        chatSessionId,
                        toJson(fillPayload),
                        buildCardMetadata(null, body, fillPayload, "coding_practice_fill", "coding-fill-card", "fill-card"),
                        "fill_card"
                );
            }
        } else if ("completed".equals(result.get("status"))) {
            if (!chatSessionId.isBlank()) {
                chatMessageOperations.saveAssistantMessage(
                        chatSessionId,
                        String.valueOf(result.getOrDefault("message", "本次刷题已全部完成！")),
                        Map.of("type", "coding_practice"),
                        "text"
                );
            }
        }

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> buildCardMetadata(ChatMessageView existingMessage,
                                                  Map<String, Object> body,
                                                  Object cardPayload,
                                                  String type,
                                                  String routeLabel,
                                                  String defaultRouteSource) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (existingMessage != null && existingMessage.metadata() != null) {
            metadata.putAll(existingMessage.metadata());
        }
        metadata.put("type", type);
        metadata.put("routeLabel", routeLabel);
        String routeSource = text(body, "routeSource");
        metadata.put("routeSource", routeSource.isBlank() ? defaultRouteSource : routeSource);
        String codingSessionId = text(body, "sessionId");
        if (!codingSessionId.isBlank()) {
            metadata.put("codingSessionId", codingSessionId);
        }
        Map<?, ?> payloadMap = objectMapper.convertValue(cardPayload, Map.class);
        if (payloadMap != null) {
            Object cardId = payloadMap.get("cardId");
            if (cardId != null) {
                metadata.put("cardId", String.valueOf(cardId));
            }
        }
        return metadata;
    }

    private ChatMessageView validateCardMessage(String messageId,
                                              String chatSessionId,
                                              String codingSessionId,
                                              String cardId) {
        ChatMessageView existing = chatMessageOperations.findMessageById(messageId);
        if (existing == null) {
            return null;
        }
        if (!"assistant".equals(existing.role())) {
            return null;
        }
        if (!chatSessionId.isBlank() && !chatSessionId.equals(existing.sessionId())) {
            return null;
        }
        Map<String, Object> metadata = existing.metadata();
        if (metadata == null) {
            return null;
        }
        Object existingCodingSessionId = metadata.get("codingSessionId");
        if (!codingSessionId.isBlank() && (existingCodingSessionId == null || !codingSessionId.equals(String.valueOf(existingCodingSessionId)))) {
            return null;
        }
        Object existingCardId = metadata.get("cardId");
        if (!cardId.isBlank() && (existingCardId == null || !cardId.equals(String.valueOf(existingCardId)))) {
            return null;
        }
        return existing;
    }

    private Object parseCardPayload(ChatMessageView message) {
        if (message == null || message.content() == null || message.content().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(message.content(), Map.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化场景题卡片失败", e);
        }
    }

    private String text(Map<String, Object> body, String key) {
        if (body == null) {
            return "";
        }
        Object value = body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}







