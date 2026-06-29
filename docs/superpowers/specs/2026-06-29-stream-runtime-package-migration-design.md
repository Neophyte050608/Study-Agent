# Stream Runtime 包迁移设计

## 背景

当前项目已经在 `AGENTS.md` 中明确采用 domain-first / package-by-feature 规范，并要求 `common` 只承载少量无业务归属的基础能力。现有 `io.github.imzmq.interview.stream.runtime` 是一个顶层技术型包，包含 SSE/stream 事件发送、任务取消和观察者包装能力，不属于独立业务域。

这些类被 `interview.application`、`knowledge.application.chatstream`、`knowledge.application.observability` 和 `observability.api` 同时使用。它们不是 `chat` 或 `knowledge` 私有能力，而是跨模块的流式传输支撑，因此应归入 `common.stream`。

## 目标

- 将 `stream.runtime` 下的通用流式支撑类迁移到 `common.stream`。
- 删除顶层 `stream` 包，减少技术型顶层目录。
- 保持类名、方法签名和运行行为不变。
- 同步更新所有 import。
- 增加架构护栏，禁止未来重新新增 `io.github.imzmq.interview.stream..` 生产代码。

## 非目标

- 不重构 SSE 发送实现。
- 不改变事件名称、payload 结构、取消语义或前端协议。
- 不合并 `knowledge.application.chatstream` 业务编排逻辑。
- 不处理 `rag/core`、`graph/domain`、`intent/domain` 等后续迁移目标。

## 迁移映射

```text
io.github.imzmq.interview.stream.runtime.StreamEventEmitter
  -> io.github.imzmq.interview.common.stream.StreamEventEmitter

io.github.imzmq.interview.stream.runtime.InterviewStreamTaskManager
  -> io.github.imzmq.interview.common.stream.InterviewStreamTaskManager

io.github.imzmq.interview.stream.runtime.InterviewStreamEventType
  -> io.github.imzmq.interview.common.stream.InterviewStreamEventType

io.github.imzmq.interview.stream.runtime.InterviewSseEmitterSender
  -> io.github.imzmq.interview.common.stream.InterviewSseEmitterSender

io.github.imzmq.interview.stream.runtime.ObservableStreamEmitter
  -> io.github.imzmq.interview.common.stream.ObservableStreamEmitter
```

## 受影响调用方

- `interview.application.ChatStreamingService`
- `interview.application.InterviewStreamingService`
- `knowledge.application.chatstream.*`
- `knowledge.application.chatstream.handler.*`
- `knowledge.application.observability.RagTraceEventBus`
- `observability.api.RagTraceStreamController`

## 测试策略

- 更新/新增 `ArchitectureRulesTest`，断言生产代码不得位于 `io.github.imzmq.interview.stream..`。
- 先运行该测试确认当前旧包会失败。
- 迁移包和 import 后运行：
  - `mvn -q -Dtest=ArchitectureRulesTest test`
  - `mvn -q -DskipTests compile`
- 如果 compile 暴露遗漏 import，继续修复直到通过。

## 风险与降级

风险主要来自 import 遗漏；本次不改变方法签名和 Bean 名称，Spring 注入按类型仍可工作。若迁移后编译或架构测试失败，回滚本批包迁移即可，不影响其它模块。
