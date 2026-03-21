# InterviewReview V2 规格（分阶段迭代版：MCP + A2A + Skill 按需加载）

## 1. 核心目标
V2 目标是把当前可用系统升级为“能力可发现、调用可治理、协作可追踪、上线可灰度”的平台化版本：
1. MCP 成为标准能力接入层（发现、调用、权限、审计）。
2. A2A 从状态通知升级为事件驱动协作（请求、消费、回传、重放）。
3. TaskRouter 升级为注册式能力目录（版本、标签、灰度）。
4. Skill 体系保持“元信息轻载 + 命中时全量加载”，并做成可观测与可治理。
5. 安全、观测、运维形成发布级闭环。

## 2. 参考基线（来自 `d:\Practice\ai`）
### 2.1 MCP 参考
1. `spring-ai-mcp/mcp-client`：`ChatClient` + `toolCallbacks` 注入模式。
2. `spring-ai-mcp/mcp-server-weather`：`@Tool` 注册、SSE/STDIO 双传输。
3. `spring-ai-mcp/nacos-mcp-server` 与 `nacos-mcp-client`：注册中心发现与动态接入。

### 2.2 观测参考
1. `spring-ai-observability-tracing`：Zipkin + trace 贯穿。
2. `spring-ai-observability-metric`：Actuator + Prometheus 指标暴露。

## 3. 范围
### 3.1 In Scope
1. MCP Gateway（主系统）+ 两类能力接入（Obsidian、Code Execution）。
2. A2A 事件语义、错误码分层、消费编排、DLQ 审计增强。
3. Skill 版本化索引、按需全量加载、缓存失效与观测指标。
4. 认证鉴权与运维接口保护（RBAC + 审计）。
5. 指标、追踪、审计三类观测上线。
6. 保持 `/api/start` `/api/answer` `/api/report` `/api/task/dispatch` 兼容。

### 3.2 Out of Scope
1. 前端框架迁移与视觉重构。
2. 多云部署编排。
3. 模型供应商策略中心化平台。

## 4. 现状差距
1. MCP 在主系统仍未形成真实调用闭环。
2. A2A 具备总线与幂等，但关键业务编排仍偏同步。
3. Skill 已有按需读取，但缺版本化、热更新、多实例一致性与指标化。
4. 运维接口缺统一鉴权与分级授权。
5. trace 主要在内存，跨实例排障能力不足。

## 5. 目标架构（V2）
### 5.1 接入层
1. 保留现有 controller。
2. 增加 AuthN/AuthZ（JWT 或 OAuth2 Resource Server + RBAC）。

### 5.2 路由层
1. TaskRouter -> CapabilityRegistry（能力名、版本、标签、灰度、超时策略）。
2. 路由可配置化，逐步替换硬编码 switch。

### 5.3 协作层（A2A）
1. 保持 `inmemory|rocketmq` 双总线。
2. 事件意图统一：`REQUEST_CAPABILITY`、`CAPABILITY_RESULT`、`CAPABILITY_FAILED`、`AUDIT_EVENT`。
3. 错误码统一：`RETRYABLE_*`、`NON_RETRYABLE_*`，并关联重试/DLQ策略。

### 5.4 MCP 层
1. `McpCapabilityGateway` 统一接口：
   - `discoverCapabilities()`
   - `invoke(capability, args, context)`
   - `authorize(user, capability)`
   - `audit(record)`
2. 首批接入：
   - Obsidian MCP（知识/笔记）
   - Code Execution MCP（编译/运行/测试）

### 5.5 Skill 层
1. 启动阶段仅加载 skill 元信息（name/description/version/tags/path/lastModified）。
2. 命中能力时再全量加载 skill 内容并缓存。
3. 失效策略：`lastModified + checksum` 双判定。
4. 多实例阶段引入二级缓存（可选 Redis）与主动失效机制。

### 5.6 观测层
1. 指标：吞吐、失败率、重试率、DLQ 重放率、幂等命中率、MCP 成功率。
2. Skill 专项指标：加载耗时、缓存命中率、失效次数、缺失次数。
3. traceId 贯穿 API -> A2A -> MCP -> Skill 调用。

## 6. 分阶段迭代策略（慢速推进）
### Iteration 1：安全与治理基线
1. 接入鉴权框架并保护运维接口。
2. 增加运维写操作审计日志。
3. 验收：历史主流程不受影响。

### Iteration 2：MCP 接入最小闭环
1. 落地 MCP Gateway 与 Obsidian MCP。
2. 增加调用超时、重试、熔断、降级。
3. 验收：一条真实业务链路经 MCP 完成调用。

### Iteration 3：A2A 事件驱动升级
1. 引入请求/结果/失败事件编排。
2. 落地可重试与不可重试错误码。
3. 验收：失败路径可重放且可审计。

### Iteration 4：Skill 按需全量加载 V2
1. 扩展 Skill 元信息索引字段（version/tags/owner）。
2. 增加按需全量加载观测指标与缓存治理。
3. 验收：常驻内存只保留元信息，详情按需加载。

### Iteration 5：Code Execution MCP + 观测收口
1. 刷题接入代码执行 MCP。
2. 指标与 tracing 全量打通。
3. 验收：质量闸门、回归、压测通过。

## 7. 兼容与迁移
1. API 合同保持兼容。
2. 路由双轨（旧 switch + 新 registry）并行一段时间后切换。
3. 画像与既有数据继续兼容读取。

## 8. 风险与缓解
1. 风险：MCP 依赖外部服务波动  
   缓解：连接超时、熔断、降级、重试预算。
2. 风险：A2A 异步化排障复杂  
   缓解：traceId 全链路贯穿 + 审计落库 + 可视化指标。
3. 风险：Skill 动态加载引发一致性问题  
   缓解：版本化键 + checksum + 主动失效。
4. 风险：鉴权改造影响历史调用  
   缓解：灰度开关 + 白名单过渡。
