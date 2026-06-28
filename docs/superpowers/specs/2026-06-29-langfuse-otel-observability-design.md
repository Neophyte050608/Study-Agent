# Langfuse OpenTelemetry Observability Design

## 背景

Study-Agent 已经具备本地 RAG trace、`AiObservationEvent`、`AiObservationPublisher`、`NoopAiObservationPublisher` 和 `TraceAttributeSanitizer.sanitizeForExternalObservation(...)`。这些能力把业务链路和外部观测平台隔开，适合在不改 RAG/Agent 主流程的前提下接入 Langfuse。

Langfuse 当前对 Java/Spring Boot 场景更适合通过 OpenTelemetry 导出 trace，而不是让业务代码直接依赖 Langfuse SDK。因此第一版采用 OpenTelemetry OTLP HTTP exporter，将内部 AI observation event 映射为 OpenTelemetry span，再由 Langfuse OTLP endpoint 接收。

## 目标

- 通过 OpenTelemetry 将安全的 AI/RAG observation event 导出到 Langfuse。
- 保持业务代码只依赖 `AiObservationPublisher`，不直接 import Langfuse 或 OpenTelemetry SDK。
- 默认关闭外部导出；未配置、配置错误或 Langfuse 不可用时，不影响应用启动和用户请求。
- 只导出经过外部观测白名单清洗的 attributes，不默认外传 raw prompt、raw completion、用户 ID、会话 ID、任务 ID、候选模型 ID 或原始错误文本。
- 为后续 Agent、Tool、Model Routing、Prompt/Evaluation 接入保留稳定扩展点。

## 非目标

- 不接入 Langfuse Prompt Management、Dataset、Score/Evaluation 写入。
- 不引入 Langfuse Java API Client。
- 不启用全链路 HTTP/MySQL/Redis/Milvus/Neo4j 自动探针。
- 不替换现有本地 RAG trace 表、SSE trace 页面或 `RAGObservabilityService`。
- 不默认导出 prompt/completion 原文。

## 包结构

新增基础设施包：

```text
src/main/java/io/github/imzmq/interview/observability/infrastructure/otel
```

建议文件：

```text
OtelObservationProperties.java
OtelAiObservationPublisher.java
OtelObservationMapper.java
OtelObservationConfig.java
```

职责：

- `OtelObservationProperties`：读取 `app.observability.external.*` 配置。
- `OtelObservationConfig`：在配置启用且必要参数存在时创建 OpenTelemetry exporter 和 `AiObservationPublisher` bean。
- `OtelAiObservationPublisher`：实现 `AiObservationPublisher`，把内部事件转换成 OTel span 并 best-effort 发送。
- `OtelObservationMapper`：集中维护内部字段到 OpenTelemetry attributes 的映射，避免散落在 publisher 中。

## 配置设计

第一版配置默认关闭：

```yaml
app:
  observability:
    external:
      enabled: false
      provider: langfuse-otel
      endpoint: ${LANGFUSE_OTEL_ENDPOINT:}
      public-key: ${LANGFUSE_PUBLIC_KEY:}
      secret-key: ${LANGFUSE_SECRET_KEY:}
      service-name: study-agent
      export-prompts: false
      export-completions: false
```

启用条件：

- `enabled=true`
- `provider=langfuse-otel`
- `endpoint` 非空
- `public-key` 和 `secret-key` 非空

任一条件不满足时，继续使用 `NoopAiObservationPublisher`。

Endpoint 必须显式配置，避免默认把本地或测试数据发到错误的 Langfuse Cloud 区域。常见值：

```text
EU Cloud:      https://cloud.langfuse.com/api/public/otel
US Cloud:      https://us.cloud.langfuse.com/api/public/otel
Self-hosted:   https://<your-langfuse-host>/api/public/otel
```

Java 实现应使用 OTLP/HTTP exporter，不使用 OTLP/gRPC。认证使用 HTTP Basic Auth：

```text
Authorization: Basic base64(public-key:secret-key)
```

如果使用 signal-specific traces endpoint，则必须保持同一 host 和 `/api/public/otel` 前缀，例如：

```text
https://<langfuse-host>/api/public/otel/v1/traces
```

## 数据流

```text
DefaultTraceService / future Agent or Tool publisher
  -> AiObservationPublisher
  -> OtelAiObservationPublisher
  -> OpenTelemetry span/exporter
  -> Langfuse OTLP endpoint
  -> Langfuse UI
```

当前第一版主要承接 `rag.node` 事件：

- `traceId`：用于内部关联，不默认直接作为 Langfuse 用户/session 维度。
- `nodeId`：仅作为内部 span id/hash/attribute 的受控映射来源，不直接暴露业务语义。
- `category`：映射为 span category / observation type 辅助字段。
- `name`：必须受控；如果未来节点名可能含用户输入，应改为固定枚举名。
- `status`：映射为 OTel span status 和 `ai.status` attribute。
- `attributes`：只使用 `sanitizeForExternalObservation(...)` 后的结果。

## Attribute 映射

第一版使用项目内稳定字段，避免绑定过深的外部语义：

```text
ai.event_type       <- event.eventType
ai.category         <- event.category
ai.status           <- event.status
ai.model            <- attributes.model
ai.provider         <- attributes.provider
ai.latency_ms       <- attributes.latencyMs / completionMs
ai.prompt_tokens    <- attributes.promptTokens
ai.completion_tokens<- attributes.completionTokens
ai.total_tokens     <- attributes.totalTokens
ai.estimated_cost   <- attributes.estimatedCost
rag.doc_count       <- attributes.docCount
rag.retrieval_mode  <- attributes.retrievalMode
agent.task_type     <- attributes.taskType
agent.route_source  <- attributes.routeSource
```

OpenTelemetry/GenAI semantic conventions可以后续再补充，但第一版不强行把所有字段映射到不稳定或不确定的语义键。所有映射都集中在 `OtelObservationMapper` 中，方便未来调整。

## 安全与隐私

- 不导出 `sessionId`、`userId`、`taskId`、`candidateId`、`nodeName`、`fallbackReason`。
- 不导出 raw prompt、raw completion、用户原文、文件内容、Authorization、cookie、API key、token。
- 不导出原始异常文本；失败只导出低信息量状态，例如 `ERROR`。
- `export-prompts=false` 和 `export-completions=false` 是第一版固定默认值，即使配置项存在，也不在本阶段真正导出原文。
- Adapter 捕获所有导出异常，只写受控 warn/debug 日志，不向业务链路抛出。

## 错误处理与降级

- OpenTelemetry 初始化失败：记录一次 warn，回退 Noop publisher。
- 单次 publish 失败：吞掉异常，记录低噪声日志。
- Langfuse endpoint 超时或不可用：不阻塞 RAG/Agent 主流程。
- 应用关闭时：尽量 flush/shutdown exporter，但 shutdown 失败不影响应用退出。

## 测试策略

- `OtelObservationPropertiesTest`：验证默认关闭、必要配置校验。
- `OtelObservationMapperTest`：验证字段映射、敏感字段不出现。
- `OtelAiObservationPublisherTest`：使用 fake exporter/span sink 验证 publish 不抛异常、失败降级。
- `AiObservationPublisherConfig` 相关测试：验证未启用时使用 Noop，启用且配置完整时使用 OTel publisher。
- `OtelObservationConfigTest`：验证 endpoint、Basic Auth header、OTLP/HTTP exporter 配置正确；禁用或缺少 key 时不创建 OTel publisher。

如完整 `mvn test` 仍受 Mockito inline / ByteBuddy self-attach 环境问题影响，本阶段至少运行新增测试和相关配置测试，并明确记录全量测试限制。

## 后续演进

- Agent action 和 `ToolExecutionPort` 可发布 `agent.step`、`tool.call` 事件。
- Model Routing 可发布 `model.generation`、`model.fallback`、`model.health` 事件。
- Prompt Management、Evaluation/Score、Dataset 接入单独设计，不混入第一版 adapter。
- OpenTelemetry 系统级自动探针单独阶段接入，避免一次性改变启动方式和依赖复杂度。
