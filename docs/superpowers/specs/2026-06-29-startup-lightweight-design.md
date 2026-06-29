# Study-Agent 启动入口轻量化设计

## 背景

当前 `InterviewReviewApplication` 本身很薄，但应用启动时会通过 Spring 自动扫描激活较多组件，包括 IM WebSocket、模型预热、模型健康探测、自动总结、检索预热等。随着 RAG、Agent、MCP、观测和多模态能力增加，启动时到底启用了什么不够直观，本地开发也容易被非必要外部连接拖慢。

本阶段目标是做低风险的入口轻量化治理：保留现有 Spring Boot 模式，不重写主入口，不拆业务模块，只给启动期重任务增加明确开关，并提供启动诊断日志。

## 目标

- 根包只保留 `InterviewReviewApplication`，避免配置类继续堆在根包。
- 默认关闭 IM 长连接，避免普通启动主动连接飞书或 QQ WebSocket。
- 给启动预热、模型预热、模型健康探测、自动总结等后台启动行为增加显式开关。
- 增加启动诊断组件，在应用 ready 后输出关键模块启停状态。
- 保持现有业务 API、RAG、Agent、模型路由主流程不变。

## 非目标

- 不拆 Controller、Service 或 RAG/Agent 主流程。
- 不引入新运行时框架。
- 不替换 Spring Boot 自动扫描。
- 不改变生产环境已经显式配置的能力；只调整默认值和增加开关。

## 设计

### 根包清理

将 `ModelSelectionConfig` 从：

```text
src/main/java/io/github/imzmq/interview/ModelSelectionConfig.java
```

迁移到：

```text
src/main/java/io/github/imzmq/interview/config/model/ModelSelectionConfig.java
```

类名和 Bean 行为保持不变，降低迁移风险。

### 配置开关

新增或调整配置：

```yaml
app:
  startup:
    model-preheat-enabled: ${APP_STARTUP_MODEL_PREHEAT_ENABLED:false}
  model-routing:
    probe:
      enabled: ${APP_MODEL_ROUTING_PROBE_ENABLED:false}
  dream:
    enabled: ${APP_DREAM_ENABLED:false}

im:
  qq:
    use-ws: ${IM_QQ_USE_WS:false}
  feishu:
    use-ws: ${IM_FEISHU_USE_WS:false}
```

`RetrievalWarmupRunner` 继续使用现有 `app.knowledge.retrieval.warmup-enabled`。`application-local-lite.yml` 保持或强化轻量默认值。

### 启动诊断

新增 `bootstrap` 包：

```text
src/main/java/io/github/imzmq/interview/bootstrap/StartupDiagnostics.java
src/main/java/io/github/imzmq/interview/bootstrap/StartupDiagnosticsProperties.java
```

`StartupDiagnostics` 监听 `ApplicationReadyEvent`，只读取配置并打印低噪声日志，不连接外部系统，不影响启动结果。输出字段包括 active profiles、RAG warmup、model preheat、model probe、dream、QQ WS、Feishu WS、Langfuse OTel。

## 测试策略

- 新增 `StartupDiagnosticsPropertiesTest` 验证默认轻量配置值。
- 新增 `StartupDiagnosticsTest` 验证诊断快照能正确读取环境属性。
- 调整或新增配置测试，确认 IM WS 默认值来自环境变量且默认关闭。
- 运行 focused tests 与 `mvn -q -DskipTests compile`。

## 风险与降级

- 配置默认关闭可能影响依赖默认 WebSocket 的使用者；通过环境变量 `IM_QQ_USE_WS=true`、`IM_FEISHU_USE_WS=true` 可恢复。
- 模型预热和健康探测默认关闭后，首次调用模型可能承担初始化成本；生产环境可显式开启。
- 启动诊断只读配置，不参与业务逻辑；如日志异常也不应影响应用启动。
