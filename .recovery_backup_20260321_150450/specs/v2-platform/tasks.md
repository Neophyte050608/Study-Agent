# InterviewReview V2 任务拆解（分步骤慢速迭代）

## Iteration 1 - 安全与运维基线
状态：✅ 已完成（2026-03-19）
### 目标
先把平台安全底座稳住，再推进 MCP/A2A 复杂改造。

### 任务
1. T1.1 接入 Spring Security 资源服务器能力。
2. T1.2 为 `/api/observability/**`、`/api/task/dispatch` 增加鉴权与角色控制。
3. T1.3 增加运维写操作审计日志（操作者、时间、参数摘要、结果）。

### 验收
1. 高风险接口默认受保护。
2. 非授权访问返回明确错误码。
3. 历史主链路可正常回归。

---

## Iteration 2 - MCP 最小可用闭环
状态：🟡 部分完成（MCP Gateway + Stub/Bridge/SSE/STDIO 四通道 + 超时重试 + traceId日志检索 + 失败回退分层 + 不可恢复错误分类已落地）
### 目标
在主系统落地第一个可发布的 MCP 调用链路。

### 任务
1. T2.1 实现 MCP Gateway（发现、调用、参数校验、统一返回）。
2. T2.2 接入 Obsidian MCP 能力并接到现有知识流。
3. T2.3 增加 MCP 调用超时、重试、熔断与降级策略。
4. T2.4 增加不可恢复错误分类（参数错误、权限错误、协议不兼容）并接入审计检索过滤。
5. T2.5 接入标准 MCP 协议传输（SSE/STDIO）并支持模式切换。

### 参考
1. `d:\Practice\ai\spring-ai-mcp\mcp-client`
2. `d:\Practice\ai\spring-ai-mcp\mcp-server-weather`

### 验收
1. 至少一条业务路径完成真实 MCP 调用。
2. 外部 MCP 不可用时系统可降级。

---

## Iteration 3 - A2A 事件驱动升级
状态：⬜ 未开始
### 目标
让 A2A 承担真正的异步编排，而不只是状态发布。

### 任务
1. T3.1 扩展事件意图：`REQUEST_CAPABILITY`、`CAPABILITY_RESULT`、`CAPABILITY_FAILED`。
2. T3.2 实现可重试/不可重试错误码分层与重试分流。
3. T3.3 增强 DLQ 重放审计与批量统计。

### 验收
1. 关键任务支持异步消费 + 回传结果。
2. 失败链路可重放、可审计、可追踪。

---

## Iteration 4 - Skill 按需全量加载 V2
状态：⬜ 未开始
### 目标
保持“平时轻载，命中全量加载”，并提升治理能力。

### 任务
1. T4.1 扩展 Skill 元信息索引（version/tags/owner/checksum）。
2. T4.2 保持详情按需全量加载，不在启动阶段加载全文。
3. T4.3 增加缓存失效策略（lastModified + checksum）。
4. T4.4 增加 Skill 专项指标（加载耗时、命中率、失效次数、缺失次数）。

### 验收
1. 常驻内存仅元信息。
2. Skill 详情只在需要时加载并可观测。

---

## Iteration 5 - 路由注册化与 Code Execution MCP
状态：⬜ 未开始
### 目标
完成路由平台化并接入真实代码执行能力。

### 任务
1. T5.1 TaskRouter 升级为 CapabilityRegistry（能力名、版本、灰度标签）。
2. T5.2 接入 Code Execution MCP（编译/运行/测试）。
3. T5.3 将执行结果结构化回写到画像事件与训练建议。

### 参考
1. `d:\Practice\ai\spring-ai-tool-calling`
2. `d:\Practice\ai\spring-ai-mcp\nacos-mcp-client`

### 验收
1. 新能力接入无需改核心 switch。
2. 刷题评估具备真实执行结果。

---

## Iteration 6 - 观测收口与发布验收
状态：⬜ 未开始
### 目标
用统一质量闸门完成发布前收口。

### 任务
1. T6.1 打通指标与 tracing（API/A2A/MCP/Skill 全链路）。
2. T6.2 完成单测、集成、回归、压测与故障演练。
3. T6.3 文档与清单对齐，形成发布手册。

### 参考
1. `d:\Practice\ai\spring-ai-observability\spring-ai-observability-metric`
2. `d:\Practice\ai\spring-ai-observability\spring-ai-observability-tracing`

### 验收
1. `mvn test` 通过。
2. V2 验收清单全部勾选。

---

## 执行节奏建议
1. 每个 Iteration 独立开发、独立回归、独立验收。
2. 每次只推进一个 Iteration，避免并行改造放大风险。
3. 每次迭代结束后更新 `specs/v2-platform/checklist.md` 与进展记录。
