package io.github.imzmq.interview.im.application.service;

import io.github.imzmq.interview.im.application.config.QqProperties;
import io.github.imzmq.interview.im.domain.UnifiedMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * QQ 长连接服务
 * 负责与 QQ 开放平台建立 WebSocket 连接，接收事件并转发处理
 */
@Service
public class QqWsService {

    private static final Logger log = LoggerFactory.getLogger(QqWsService.class);
    private static final long QQ_INTENTS = 1_140_855_296L;
    
    private final QqProperties qqProperties;
    private final QqEventParser qqEventParser;
    private final ImWebhookService imWebhookService;
    private final QqAuthTokenProvider qqAuthTokenProvider;
    private final ObjectMapper objectMapper;
    
    private WebSocketSession currentSession;
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Object sendLock = new Object();
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile Long lastSequence;
    private volatile long heartbeatIntervalMillis = 25000L;

    // 沙箱环境网关 (生产环境需替换或动态获取)
    private static final String GATEWAY_URL = "wss://sandbox.api.sgroup.qq.com/websocket";

    public QqWsService(QqProperties qqProperties, QqEventParser qqEventParser, ImWebhookService imWebhookService, QqAuthTokenProvider qqAuthTokenProvider, ObjectMapper objectMapper) {
        this.qqProperties = qqProperties;
        this.qqEventParser = qqEventParser;
        this.imWebhookService = imWebhookService;
        this.qqAuthTokenProvider = qqAuthTokenProvider;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (!qqProperties.isUseWs()) {
            log.info("【QQ WS】长连接模式未开启，跳过初始化。");
            return;
        }
        connect();
    }

    private void connect() {
        try {
            log.info("【QQ WS】开始连接网关: {}", GATEWAY_URL);
            StandardWebSocketClient client = new StandardWebSocketClient();
            client.doHandshake(new QqWebSocketHandler(), GATEWAY_URL).completable().whenComplete((session, ex) -> {
                if (ex != null) {
                    log.error("【QQ WS】连接失败", ex);
                    scheduleReconnect();
                } else {
                    log.info("【QQ WS】连接成功");
                    this.currentSession = session;
                    // 发送鉴权 Payload
                    sendAuthPayload(session);
                }
            });
        } catch (Exception e) {
            log.error("【QQ WS】建立连接异常", e);
            scheduleReconnect();
        }
    }

    private void sendAuthPayload(WebSocketSession session) {
        try {
            // QQ Bot 鉴权协议
            String token = qqAuthTokenProvider.resolveGatewayToken();
            String payload = String.format("""
                {
                  "op": 2,
                  "d": {
                    "token": "%s",
                    "intents": %d,
                    "properties": {
                      "$os": "windows",
                      "$browser": "interview-review",
                      "$device": "interview-review"
                    }
                  }
                }
                """, token, QQ_INTENTS);
            sendTextSafely(session, payload);
        } catch (Exception e) {
            log.error("【QQ WS】发送鉴权失败", e);
        }
    }

    /**
     * 统一发送文本，避免并发写入导致底层 WebSocket 状态机异常。
     */
    private void sendTextSafely(WebSocketSession session, String payload) {
        synchronized (sendLock) {
            try {
                if (session != null && session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (Exception e) {
                log.error("【QQ WS】发送消息失败", e);
            }
        }
    }

    /**
     * 启动心跳任务，按照服务端下发间隔定时发送 op=1。
     */
    private void scheduleHeartbeat(WebSocketSession session, long intervalMillis) {
        cancelHeartbeat();
        heartbeatIntervalMillis = Math.max(intervalMillis, 1000L);
        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(() -> {
            String seq = lastSequence == null ? "null" : String.valueOf(lastSequence);
            String heartbeatPayload = "{\"op\":1,\"d\":" + seq + "}";
            sendTextSafely(session, heartbeatPayload);
        }, heartbeatIntervalMillis, heartbeatIntervalMillis, TimeUnit.MILLISECONDS);
        log.info("【QQ WS】已启动心跳任务，间隔 {} ms", heartbeatIntervalMillis);
    }

    /**
     * 取消当前心跳任务。
     */
    private void cancelHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
            heartbeatFuture = null;
        }
    }

    private void scheduleReconnect() {
        log.info("【QQ WS】将在 5 秒后尝试重连...");
        reconnectExecutor.schedule(this::connect, 5, TimeUnit.SECONDS);
    }

    private class QqWebSocketHandler extends TextWebSocketHandler {
        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            String payload = message.getPayload();
            JsonNode rootNode = objectMapper.readTree(payload);

            if (rootNode.has("s") && !rootNode.get("s").isNull()) {
                lastSequence = rootNode.get("s").asLong();
            }
            
            int op = rootNode.has("op") ? rootNode.get("op").asInt() : -1;
            
            // 收到 Hello 后启动定时心跳，避免在读线程中并发写导致 TEXT_PARTIAL_WRITING
            if (op == 10) {
                long interval = heartbeatIntervalMillis;
                JsonNode dNode = rootNode.get("d");
                if (dNode != null && dNode.has("heartbeat_interval")) {
                    interval = dNode.get("heartbeat_interval").asLong(heartbeatIntervalMillis);
                }
                scheduleHeartbeat(session, interval);
                return;
            }

            // 事件分发 (op 0)
            if (op == 0) {
                String eventType = rootNode.has("t") && !rootNode.get("t").isNull() ? rootNode.get("t").asText() : "unknown";
                log.info("【QQ WS】收到事件: {}", eventType);
                UnifiedMessage unifiedMessage = qqEventParser.parseMessageEvent(rootNode);
                if (unifiedMessage != null) {
                    if (imWebhookService.tryRecordEvent(unifiedMessage.getEventId())) {
                        imWebhookService.dispatchMessageAsync(unifiedMessage);
                    } else {
                        log.info("【QQ WS】检测到重复事件，跳过分发: {}", unifiedMessage.getEventId());
                    }
                }
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            log.warn("【QQ WS】连接已关闭: {}", status);
            if (status != null && status.getCode() == 4004) {
                qqAuthTokenProvider.invalidate();
            }
            cancelHeartbeat();
            scheduleReconnect();
        }
    }
}



