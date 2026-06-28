# AI/RAG 可观测性开发规范

本文档说明 Study-Agent 当前 AI、RAG 和 Agent 可观测性的实现边界，以及后续接入 Langfuse、OpenTelemetry 等外部观测系统时必须遵守的 adapter 规则。

## 当前实现

当前本地 RAG trace 主链路由 `knowledge.application.observability.RAGObservabilityService` 承担：

- 记录 RAG/Agent 节点的开始、结束、耗时、状态、检索数量、fallback 等信息。
- 将 trace summary 和 node detail 持久化到本地 trace 表；数据库不可用时回退到内存历史。
- 提供最近 trace、活动 trace、单条 trace 详情和概览统计查询。
- 通过 `RagTraceEventBus` 与 `RagTraceStreamController` 对外提供 `GET /api/observability/rag-traces/{traceId}/stream` SSE 事件流。

本地 trace 面向开发调试和前端观测页面，属于项目内部能力，不等同于外部观测平台。

## 字段脱敏规则

当前 trace 写入存在两条路径，脱敏边界不同，不能把已迁移链路的约束描述成全量现状。

### `TraceService` / `DefaultTraceService` 路径

新增或改造代码必须优先走 `TraceService` / `DefaultTraceService`。这条路径负责在写入本地 trace 和发布外部观测事件前统一调用 `observability.application.TraceAttributeSanitizer`：

- 本地 trace summary/detail 使用 `TraceAttributeSanitizer.sanitize(...)`。
- external observation attributes 使用 `TraceAttributeSanitizer.sanitizeForExternalObservation(...)`。

`sanitize(...)` 只保留本地允许展示的字段，并截断过长值。它可以保留部分本地排障需要的关联字段，例如 `sessionId`、`userId`、`taskId`、`candidateId`、`nodeName`、`fallbackReason` 等，但新增或改造代码不得写入密钥、token、Authorization header、cookie、raw prompt、raw completion 或用户隐私原文。

### 旧有/直接 `RAGObservabilityService` 路径

当前仍存在旧有代码直接调用 `knowledge.application.observability.RAGObservabilityService.endNode(...)` 并传入摘要字符串的路径。这些调用方尚未全量统一经过 `TraceAttributeSanitizer`，因此不能声称当前本地 trace 已经完全不会写入 prompt 或用户原文。

迁移目标是逐步将这类调用收敛到 `TraceService` / `DefaultTraceService`。如果短期内不得不直接调用 `RAGObservabilityService`，调用方必须只传安全摘要，不能传 raw prompt、raw completion、用户原文、token、Authorization header、cookie、API key 或其他敏感/可识别内容。

### 外部观测导出

导出到外部观测系统前，`DefaultTraceService` 路径必须使用：

```java
TraceAttributeSanitizer.sanitizeForExternalObservation(...)
```

该方法用于生成外部观测 attributes，会过滤更敏感或更容易跨系统关联的字段，包括但不限于：

- `sessionId`
- `userId`
- `taskId`
- `candidateId`
- `nodeName`
- `fallbackReason`
- raw error / caller error message
- caller-provided `errorType`

外部事件中如需表达失败，只允许由发布链路写入低信息量状态字段或统一错误类型，例如当前 `DefaultTraceService` 在失败时写入 `errorType=ERROR`。业务代码不能把原始异常文本、调用方传入的错误类型或可识别用户/会话/任务的 ID 直接塞进外部观测 attributes。

`sanitizeForExternalObservation(...)` 仅清洗 `AiObservationEvent.attributes`。`AiObservationEvent.traceId`、`nodeId`、`category`、`name`、`status` 属于内部事件元数据，不等于可直接外传字段；其中 `name` 可能是内部节点名称。外部 adapter 必须显式映射这些顶层字段，不得默认原样外传内部 ID、节点名称，或未来可能承载用户输入的字段。如需向外部系统传递 `traceId` / `nodeId`，必须使用内部生成 ID、hash/alias 或受控映射策略，并先在本文档补充对应规则。

## 外部观测端口

`observability.application.AiObservationPublisher` 是 framework-neutral 端口。RAG、模型路由、Agent 等业务链路只依赖该端口或更上层的 trace service，不直接关心事件最终写入哪里。

`observability.application.NoopAiObservationPublisher` 是默认 fallback：

- 不连接 Langfuse、OpenTelemetry Collector 或任何外部系统。
- 只在 debug 日志中记录事件被忽略。
- 必须保证本地开发、测试和未配置外部观测时主流程不受影响。

## 禁止直接依赖外部 SDK

业务代码禁止直接依赖 Langfuse、OpenTelemetry 等外部 SDK：

- Controller、application service、domain service、RAG pipeline、Agent runtime、模型路由不能直接 import Langfuse/OpenTelemetry SDK。
- 外部 SDK 只允许出现在明确的 infrastructure adapter 中。
- 业务代码只发布项目内部事件或调用 `AiObservationPublisher` 端口。
- 外部观测失败必须被 adapter 捕获和降级，不能影响 RAG、模型调用、Agent 执行或用户请求主流程。

## 后续 Langfuse adapter 规则

后续接入 Langfuse 时，应新增类似：

```text
observability.infrastructure.langfuse.LangfuseAiObservationPublisher
```

该 adapter 通过配置启用，默认不开启；未配置或初始化失败时回退 `NoopAiObservationPublisher`。实现要求：

- 只能将经过 `sanitizeForExternalObservation(...)` 清洗的 `AiObservationEvent.attributes` 作为外部 attributes；`traceId`、`nodeId`、`category`、`name`、`status` 等顶层字段需单独显式映射。
- 不得默认原样外传内部 ID、节点名称，或未来可能承载用户输入的顶层字段；如需传递 `traceId` / `nodeId`，必须使用内部生成 ID、hash/alias 或受控映射策略，并先在本文档补充对应规则。
- 不在 adapter 内补充用户 ID、会话 ID、任务 ID、候选模型 ID、节点名称、原始错误文本等敏感/可关联字段。
- 发送失败只记录受控日志，不向上抛出影响主流程的异常。
- 与 Langfuse 的 trace/span/generation/evaluation 映射关系需在本文件补充说明后再落地。

Langfuse 主要用于 LLM/RAG 细节追踪，例如 prompt、模型、token、cost、retrieval、generation、evaluation 的可比较记录。是否记录 prompt 原文必须另行设计开关、脱敏和权限，不得默认外传。

## Langfuse via OpenTelemetry 配置

第一版 Langfuse 接入通过 OpenTelemetry OTLP/HTTP exporter 完成，不直接使用 Langfuse Java Client。默认关闭：

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

常用 endpoint：

```text
EU Cloud:    https://cloud.langfuse.com/api/public/otel
US Cloud:    https://us.cloud.langfuse.com/api/public/otel
Self-hosted: https://<your-langfuse-host>/api/public/otel
```

认证使用 `Authorization: Basic base64(public-key:secret-key)`，由配置类生成，不要把 secret 写入日志、trace attributes 或文档示例。

当前只导出 `AiObservationEvent` 的安全 attributes。`export-prompts` 和 `export-completions` 预留但不生效，不得默认外传 prompt/completion 原文。

## OpenTelemetry 定位

OpenTelemetry 是系统级 trace/metrics/logs 能力，适合观测：

- HTTP 请求链路。
- 数据库、Redis、Milvus、Neo4j 等基础依赖。
- 外部模型 API 调用耗时和错误率。
- JVM、线程池、连接池、吞吐、延迟等系统指标。

OpenTelemetry 不替代 Langfuse 的 LLM/RAG 细节追踪。两者可以并存：

- OpenTelemetry 负责系统级调用链、指标和日志关联。
- Langfuse 或同类 adapter 负责 AI 应用层的 prompt、retrieval、generation、token、cost、evaluation 细节。
- 二者都必须通过 adapter/port 隔离，业务代码不得直接绑定具体 SDK。

## 文档同步要求

以下变更必须同步更新本文档：

- AI/RAG/Agent trace 事件类型变化。
- trace summary/detail 字段白名单变化。
- `sanitizeForExternalObservation(...)` 外部导出字段变化。
- 新增或替换外部观测 adapter。
- Langfuse/OpenTelemetry 配置、启用方式或失败降级策略变化。
