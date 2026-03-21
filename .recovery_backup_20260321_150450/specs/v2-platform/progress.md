# InterviewReview V2 迭代进展（持续更新）

## 当前结论
V2 已完成 Iteration 1，并完成 Iteration 2 的第六阶段落地（STDIO 传输接入 + 模式扩展 + 单测补充）。

## 当前实现总览
1. 安全与治理：
   - 已启用资源服务器鉴权，`/api/task/dispatch`、`/api/observability/**`、`/api/mcp/**` 受保护。
   - 运维写接口（DLQ 重放、幂等清理）具备角色控制与审计记录。
2. MCP 能力：
   - 已提供能力发现与调用入口（`/api/mcp/capabilities`、`/api/mcp/invoke`）。
   - 已支持超时重试、失败回退、traceId 透传、按 traceId 检索日志。
   - 已支持 `stub|bridge|sse|stdio|auto` 模式切换，并支持外部网关失败后回退 stub。
   - 已增加错误分层字段（`errorCode`、`retryable`）用于调用治理。
3. 学习系统能力：
   - 面试与刷题已接入统一画像写回，支持快照、推荐、事件查询。
   - 刷题出题可自动读取画像推荐主题，闭环能力已可复现。
4. 稳定性：
   - 当前全量测试通过，作为继续推进 A2A 与 Skill V2 的可用基线。

## 迭代状态
1. Iteration 1（安全与运维基线）：已完成
2. Iteration 2（MCP 最小可用闭环）：进行中（已完成 Gateway、首批能力 stub 接入、traceId 日志检索、bridge 适配、不可恢复错误分类、SSE/STDIO 传输接入）
3. Iteration 3（A2A 事件驱动升级）：未开始
4. Iteration 4（Skill 按需全量加载 V2）：未开始
5. Iteration 5（路由注册化与 Code Execution MCP）：未开始
6. Iteration 6（观测收口与发布验收）：未开始

## Iteration 1 完成项
1. 安全基线：
   - 引入 `spring-boot-starter-security` 与 `spring-boot-starter-oauth2-resource-server`。
   - 新增 `SecurityConfig`，保护 `/api/task/dispatch` 与 `/api/observability/**`。
   - 运维写接口限定 `ROLE_OPS_ADMIN`。
2. 审计能力：
   - 新增 `OpsAuditService`。
   - 新增 `GET /api/observability/audit/ops` 查询最近运维操作记录。
   - 在 DLQ 重放与幂等清理等写操作接入审计记录。
3. 测试与回归：
   - 新增/调整安全相关测试（含鉴权请求后处理器）。
   - 全量回归命令：`mvn test`。
   - 结果：`Tests run: 49, Failures: 0, Errors: 0, Skipped: 0`。

## 风险与回滚
1. 风险：生产环境 JWT 密钥配置错误导致接口拒绝访问。
2. 缓解：已提供 `app.security.jwt-secret` 环境变量入口，发布前校验配置。
3. 回滚：可临时回滚 `SecurityConfig` 与依赖改动到上一版本。

## Iteration 2 当前完成项（第一阶段）
1. 新增 `McpGatewayService`，支持能力发现、调用超时与重试、失败回退。
2. 新增 MCP API：
   - `GET /api/mcp/capabilities`
   - `POST /api/mcp/invoke`
3. `NoteMakingAgent` 新增 `useMcp=true` 路径，命中 `obsidian.write` 能力时走 MCP 写入。
4. `StubMcpCapabilityGateway` 扩展 `obsidian.write` 与 `code.execute` 能力返回。
5. 访问策略：`/api/mcp/**` 已纳入鉴权保护。

## Iteration 2 新增完成项（第二阶段）
1. MCP 调用链路增加 traceId 透传与审计记录。
2. 新增 `GET /api/observability/mcp/logs`，支持按 traceId 检索 MCP 调用日志。
3. `GET /api/mcp/capabilities` 支持携带 traceId；`POST /api/mcp/invoke` 自动补齐 traceId。
4. 新增控制器集成测试覆盖 MCP 调用与 traceId 日志查询。

## Iteration 2 新增完成项（第三阶段）
1. 新增 `McpBridgeCapabilityGateway`，支持通过外部桥接服务调用真实 MCP 能力。
2. 新增 MCP 配置项：
   - `app.mcp.mode=stub|bridge|auto`
   - `app.mcp.fallback-to-stub`
   - `app.mcp.bridge.*`（base-url/capabilities-path/invoke-path）
3. `McpGatewayService` 支持 bridge 失败后的 stub 回退，并在结果中输出 `errorCode` 与 `retryable`。
4. 新增 `McpGatewayServiceTest` 覆盖两类失败场景：
   - bridge 调用失败后回退 stub
   - bridge 模式未配置服务时回退 stub

## Iteration 2 新增完成项（第四阶段）
1. `McpBridgeCapabilityGateway` 新增不可恢复错误分类：
   - `MCP_INVALID_PARAMS`（400/422）
   - `MCP_PERMISSION_DENIED`（401/403）
   - `MCP_PROTOCOL_INCOMPATIBLE`（406/409/412/415/426）
2. `McpGatewayService` 在 MCP 审计记录中统一输出 `status/errorCode/retryable`，覆盖主网关失败、bridge 失败回退、最终降级分支。
3. `OpsAuditService` 与 `GET /api/observability/mcp/logs` 支持按 `errorCode` 过滤，形成“错误码分层 -> 审计检索”联动闭环。
4. 新增测试覆盖：
   - `McpGatewayServiceTest#shouldMarkPermissionDeniedAsNonRetryableAndAuditable`
   - `InterviewControllerFlowTest#shouldFilterMcpLogsByErrorCode`
5. 新增桥接分类测试 `McpBridgeCapabilityGatewayTest`，覆盖：
   - 4xx 不可恢复分类（参数错误、权限错误、协议不兼容）
   - 5xx 映射为可重试远端错误
   - 访问超时映射为 `MCP_TIMEOUT`
   - 网络不可达映射为 `MCP_UNREACHABLE`
   - 返回格式异常映射为 `MCP_INVALID_RESPONSE`
6. `McpBridgeCapabilityGateway` 增强 JSON-RPC 错误体收口：
   - `error.code=-32602` 映射 `MCP_INVALID_PARAMS`
   - `error.code=-32001 / PERMISSION_DENIED` 映射 `MCP_PERMISSION_DENIED`
   - `error.code=-32601 / METHOD_NOT_FOUND` 映射 `MCP_PROTOCOL_INCOMPATIBLE`
7. `McpBridgeCapabilityGateway` 增强 JSON-RPC 结果结构兼容：
   - capabilities 支持从 `result.capabilities` 解析
   - invoke 支持从 `result.result` 解析最终业务结果
   - capabilities 的 JSON-RPC `error` 也纳入统一错误映射
8. `McpBridgeCapabilityGateway` invoke 请求体增强为 JSON-RPC 兼容信封：
   - 新增 `jsonrpc/id/method/params` 字段
   - 保留 `capability/params` 兼容现有 HTTP bridge 约定
9. `McpBridgeCapabilityGateway` capabilities 查询增强为双通道兼容：
   - 优先使用 JSON-RPC POST（`method=tools/list`）
   - 遇到 404/405/501 或协议不兼容时自动回退 GET
10. `McpCapabilityGateway` 接口支持透传调用上下文，Bridge 会优先使用 `traceId` 生成 JSON-RPC `id`，便于与审计链路对齐。
11. Bridge 调用会透传 `X-Trace-Id` 请求头（当 context 含 traceId 时），用于远端日志与本地审计对齐。

## Iteration 2 新增完成项（第五阶段）
1. 新增 `McpSseCapabilityGateway`，按标准 MCP JSON-RPC 方法接入 SSE/Streamable HTTP：
   - capabilities 使用 `tools/list`
   - invoke 使用 `tools/call`
2. `McpGatewayService` 新增 `sse` 模式，并在 `auto` 模式下优先使用 SSE，再回退 bridge/stub。
3. 新增配置项：
   - `app.mcp.sse.enabled`
   - `app.mcp.sse.base-url`
   - `app.mcp.sse.endpoint-path`
4. 新增测试覆盖：
   - `McpSseCapabilityGatewayTest`（请求信封、traceId 透传、JSON-RPC 错误映射、超时/不可达映射）
   - `McpGatewayServiceTest`（sse 模式路由、sse 未配置回退、auto 优先级）

## Iteration 2 新增完成项（第六阶段）
1. 新增 `McpStdioCapabilityGateway`，按标准 MCP JSON-RPC 方法接入 STDIO：
   - capabilities 使用 `tools/list`
   - invoke 使用 `tools/call`
   - 进程启动失败/超时/返回格式异常映射到统一错误码
2. `McpGatewayService` 新增 `stdio` 模式，并在 `auto` 模式下增加 `stdio` 优先级。
3. 新增配置项：
   - `app.mcp.stdio.enabled`
   - `app.mcp.stdio.command`
   - `app.mcp.stdio.args`
   - `app.mcp.stdio.timeout-millis`
4. 新增测试覆盖：
   - `McpStdioCapabilityGatewayTest`（请求写入、响应解析、JSON-RPC 错误映射、超时、进程启动失败）
   - `McpGatewayServiceTest`（stdio 模式路由、stdio 未配置回退、auto 缺少 sse 时优先 stdio）
   - `McpGatewayServiceTest`（stdio 失败且禁用回退、auto 模式下 stdio 失败回退 stub）
   - `InterviewControllerFlowTest`（按 `MCP_TIMEOUT` 与 `MCP_INVALID_RESPONSE` 过滤审计日志）

## 验证命令
1. 全量：`mvn test`
2. 本次执行：`mvn "-Dtest=McpGatewayServiceTest,McpSseCapabilityGatewayTest,McpStdioCapabilityGatewayTest" -DfailIfNoTests=false test`
3. 本地环境现状：当前工作区存在历史语法错误文件，导致编译阶段失败（与本次改动文件无直接耦合），需先修复主干编译错误后再复核测试。

## 本次约束
1. 一次只推进一个 Iteration。
2. 每次迭代结束必须完成独立回归与 checklist 更新。
3. 每次迭代都要记录“变更点、验证命令、风险与回滚路径”。

## 后续任务计划（按优先级）
1. Iteration 2 收口（真实 MCP）：
   - 增加真实 SSE/STDIO 集成测试覆盖（超时、不可达、返回格式异常、4xx 分类）。
   - 将 Obsidian/CodeExecution 业务路径由 stub 灰度切到真实 MCP。
2. Iteration 3（A2A 事件驱动）：
   - 增加 `REQUEST_CAPABILITY / CAPABILITY_RESULT / CAPABILITY_FAILED` 事件语义。
   - 落地可重试/不可重试错误码分层与重试分流策略。
   - 增强 DLQ 审计与重放统计，形成可追踪故障闭环。
3. Iteration 4（Skill 按需全量加载 V2）：
   - 扩展 Skill 元信息索引（version/tags/owner/checksum）。
   - 完成 `lastModified + checksum` 双失效策略与加载指标埋点。
4. Iteration 5（路由注册化 + 代码执行）：
   - 将 TaskRouter 升级为 CapabilityRegistry 注册式路由。
   - 把 code.execute 从 stub 切换到真实沙箱执行并回写画像。
5. Iteration 6（观测收口）：
   - 打通 API/A2A/MCP/Skill 的 tracing 与指标看板。
   - 完成压测、故障演练与发布手册。
