package io.github.imzmq.interview.im.api;

import io.github.imzmq.interview.im.application.config.FeishuProperties;
import io.github.imzmq.interview.im.domain.UnifiedMessage;
import io.github.imzmq.interview.im.application.service.FeishuEventParser;
import io.github.imzmq.interview.im.application.service.ImWebhookService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhook/im/feishu")
public class FeishuWebhookController {

    private static final Logger log = LoggerFactory.getLogger(FeishuWebhookController.class);

    private final FeishuProperties feishuProperties;
    private final FeishuEventParser feishuEventParser;
    private final ImWebhookService imWebhookService;
    private final ObjectMapper objectMapper;

    public FeishuWebhookController(FeishuProperties feishuProperties, FeishuEventParser feishuEventParser, ImWebhookService imWebhookService, ObjectMapper objectMapper) {
        this.feishuProperties = feishuProperties;
        this.feishuEventParser = feishuEventParser;
        this.imWebhookService = imWebhookService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{appId}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable("appId") String appId,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
            @RequestBody String body) throws JsonProcessingException {

        log.debug("Received Feishu webhook for appId: {}", appId);

        // 1. Verify AppId (simple check for now)
        if (feishuProperties.getAppId() != null && !feishuProperties.getAppId().equals(appId)) {
            log.warn("AppId mismatch. Expected: {}, Actual: {}", feishuProperties.getAppId(), appId);
            return ResponseEntity.badRequest().build();
        }

        // 2. Signature verification
        if (!feishuEventParser.verifySignature(signature, timestamp, nonce, body, feishuProperties.getEncryptKey())) {
            log.warn("Signature verification failed.");
            return ResponseEntity.status(401).build();
        }

        JsonNode rootNode = objectMapper.readTree(body);

        // 3. url_verification handshake
        if (rootNode.has("type") && "url_verification".equals(rootNode.get("type").asText())) {
            String challenge = rootNode.get("challenge").asText();
            Map<String, Object> response = new HashMap<>();
            response.put("challenge", challenge);
            return ResponseEntity.ok(response);
        }

        // 4. Event processing
        if (rootNode.has("header") && rootNode.has("event")) {
            JsonNode headerNode = rootNode.get("header");
            String eventType = headerNode.get("event_type").asText();
            String eventId = headerNode.get("event_id").asText();

            // Process specific event types
            if ("im.message.receive_v1".equals(eventType)) {
                if (!imWebhookService.tryRecordEvent(eventId)) {
                    log.info("Duplicate event_id detected, fast ack: {}", eventId);
                    return ResponseEntity.ok(Map.of("code", 1, "msg", "Skip repeat event"));
                }
                UnifiedMessage message = feishuEventParser.parseMessageEvent(rootNode, appId);
                if (message != null) {
                    imWebhookService.dispatchMessageAsync(message);
                }
            } else {
                log.debug("Ignored event type: {}", eventType);
            }
        }

        // Fast ACK
        return ResponseEntity.ok(Map.of("code", 0, "msg", "success"));
    }
}



