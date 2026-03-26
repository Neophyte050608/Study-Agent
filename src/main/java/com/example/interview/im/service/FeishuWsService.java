package com.example.interview.im.service;

import com.example.interview.im.config.FeishuProperties;
import com.example.interview.im.model.UnifiedMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.ws.Client;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 飞书长连接服务 (FeishuWsService)
 * 
 * 使用飞书官方 SDK 提供的 WebSocket 模式（Socket Mode）接收事件推送。
 * 核心优势：
 * 1. 无需公网 IP 或域名映射（如 cpolar/ngrok）。
 * 2. 无需在飞书后台配置加密策略和验证 Token。
 * 3. 适合内网开发环境，响应速度快。
 * 
 * 该服务仅在配置 im.feishu.use-ws=true 时生效。
 */
@Service
@ConditionalOnProperty(prefix = "im.feishu", name = "use-ws", havingValue = "true")
public class FeishuWsService {

    /** 手动声明日志对象，避免对 Lombok @Slf4j 的编译依赖 */
    private static final Logger log = LoggerFactory.getLogger(FeishuWsService.class);

    private final FeishuProperties feishuProperties;
    private final FeishuEventParser feishuEventParser;
    private final ImWebhookService imWebhookService;
    private final ObjectMapper objectMapper;
    private Client wsClient;

    /**
     * 显式构造函数，避免依赖 Lombok @RequiredArgsConstructor 生成代码。
     */
    public FeishuWsService(FeishuProperties feishuProperties,
                           FeishuEventParser feishuEventParser,
                           ImWebhookService imWebhookService,
                           ObjectMapper objectMapper) {
        this.feishuProperties = feishuProperties;
        this.feishuEventParser = feishuEventParser;
        this.imWebhookService = imWebhookService;
        this.objectMapper = objectMapper;
    }

    /**
     * 在服务启动后初始化并开启长连接。
     * 该方法会启动一个后台线程与飞书服务器建立 WebSocket 连接。
     */
    @PostConstruct
    public void startWsClient() {
        // 验证 AppId 和 AppSecret 是否已配置
        if (feishuProperties.getAppId() == null || feishuProperties.getAppSecret() == null) {
            log.warn("【飞书长连接】由于配置项 appId 或 appSecret 缺失，无法启动 WebSocket 监听。");
            return;
        }

        try {
            // 1. 创建事件调度中心 (EventDispatcher)
            // 注意：在 oapi-sdk-java 2.2.8 中，IM 消息事件的标准监听方法是 onP2MessageReceiveV1
            // 不应使用 onP2ImMessageReceiveV1（该方法在当前版本不存在）
            EventDispatcher dispatcher = EventDispatcher.newBuilder(
                    feishuProperties.getVerificationToken(), 
                    feishuProperties.getEncryptKey())
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    /**
                     * 处理飞书消息接收事件（私聊/群聊统一入口）。
                     * 这里必须使用 Handler 子类而非 Lambda，因为当前 SDK 中该类型是抽象类。
                     */
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        log.info("【飞书长连接】接收到消息事件，消息ID: {}", event.getEvent().getMessage().getMessageId());
                        handleWsEvent(event);
                    }
                })
                .build();

            // 2. 初始化 WebSocket 客户端
            this.wsClient = new Client.Builder(feishuProperties.getAppId(), feishuProperties.getAppSecret())
                    .eventHandler(dispatcher)
                    .build();

            // 3. 异步启动长连接监听
            // 使用新线程启动 wsClient.start() 以免阻塞 Spring 容器的初始化
            new Thread(() -> {
                try {
                    log.info("【飞书长连接】正在尝试与飞书服务器建立 WebSocket 连接... AppID: {}", feishuProperties.getAppId());
                    wsClient.start();
                } catch (Exception e) {
                    log.error("【飞书长连接】WebSocket 连接运行异常，请检查网络或 App 凭证", e);
                }
            }, "Feishu-WebSocket-Pool").start();

        } catch (Exception e) {
            log.error("【飞书长连接】初始化长连接客户端失败", e);
        }
    }

    /**
     * 统一处理长连接捕获的事件对象。
     * 由于 SDK 封装的 Event 对象结构复杂，我们先将其转为 JsonNode，
     * 然后复用解析器的逻辑将其转换为 UnifiedMessage。
     * 
     * @param eventData 飞书 SDK 提供的原始事件对象
     */
    private void handleWsEvent(Object eventData) {
        try {
            // 将事件对象序列化为 JSON 字符串
            String jsonStr = objectMapper.writeValueAsString(eventData);
            log.info("【飞书长连接】收到原始事件数据: {}", jsonStr);
            JsonNode rootNode = objectMapper.readTree(jsonStr);
            
            // 转换为系统内部统一的消息模型
            UnifiedMessage message = feishuEventParser.parseMessageEventFromWs(rootNode, feishuProperties.getAppId());
            
            if (message != null) {
                if (imWebhookService.tryRecordEvent(message.getEventId())) {
                    imWebhookService.dispatchMessageAsync(message);
                } else {
                    log.info("【飞书长连接】检测到重复事件，跳过分发: {}", message.getEventId());
                }
            }
        } catch (Exception e) {
            log.error("【飞书长连接】解析 WS 事件数据失败", e);
        }
    }
}
