# InterviewReview V2 交接文档（账号切换用）

## 1. 交接目的
本文件用于你切换到新账号后，快速恢复开发上下文，避免重复排查与重复设计。

## 2. 当前总体状态
1. V2 迭代状态：
   - Iteration 1（安全与运维基线）：已完成。
   - Iteration 2（MCP 最小闭环）：已完成前端联调与功能展示，支持 Mock 参数注入与一键复制。
   - Iteration 3~6：未开始。
2. 当前前端已经全面重构并打通了真实后端数据。
3. 当前工作区存在历史编译错误，需先修复后再执行 `mvn test` 做全量回归。

## 3. 已落地能力（可直接复用）
### 3.1 安全与治理
1. 当前为方便个人使用，已移除多用户隔离与 JWT 强制校验，进入**单机单用户模式**。所有请求均会自动映射为 `default-user`。
2. 运维写操作（如 DLQ 重放、幂等清理）不再受 `ROLE_OPS_ADMIN` 约束。
3. 运维审计已接入，支持最近记录查询。

### 3.2 前端工程化与体验升级（最新进展）
1. **五个核心页面已全部静态转动态**：
   - 面试练习（`interview.html`）：去除了 footer，优化了开始按钮的 ID 选择器。
   - 知识库管理（`knowledge.html`）：修复了数据刷新问题，实现了多路径（多行文本）循环同步，并把**同步配置持久化到了后端的 `sync_config.json`** 中。
   - 学习画像（`profile.html`）：引入了 Chart.js 绘制真实的能力雷达图，并使用后端事件数据渲染了学习动态表格。
   - MCP 工具台（`mcp.html`）：打通了能力获取，底部状态栏支持真实工具数和图标动态渲染。修复了测试调用按钮的失效问题，并加入了**点击能力自动填入 Mock 参数**及**一键复制 JSON 结果**功能。
   - 观测与运维（`ops.html`）：打通了 L2 Cache 和 RAG 向量存储的真实监控指标获取。
2. **多模态交互**：面试练习页面已集成 Web Speech API，支持：
   - 每道题自动进行 AI 语音播报（TTS）。
   - 用户可通过“语音回答”按钮录制语音转写到输入框（STT）。
   - 自动在每道题开始时计时，在提交时暂停。

### 3.3 核心业务逻辑增强（最新进展）
1. **面试记录持久化**：
   - `InMemorySessionRepository` 已被改造。虽然内存中依然通过 LRU 机制只保留最近 10 次的记录，但每次更新都会自动刷入到项目根目录的 `interview_sessions.json` 文件中，并支持项目重启时通过 `@PostConstruct` 恢复，**避免了重启丢失数据的问题**。
2. **简历自动清理机制**：
   - 优化了 `InterviewController` 中的简历上传逻辑。现在上传新简历前，系统会自动扫描并**清空 `uploads/resumes/` 目录下旧的 PDF 文件**，防止本地磁盘无限增长。
3. **基于画像的“针对性刁难”出题机制**：
   - **首题生成** (`RAGService.generateFirstQuestion`)：修改了 Prompt，强制 AI 优先围绕用户的历史薄弱点（Weaknesses）或得分较低的技能出题。
   - **后续追问** (`DecisionLayerAgent.plan`)：在给大模型的内部策略指令中，强制要求必须查阅用户历史画像，优先针对历史薄弱点进行追问和出题。
   - 学习画像数据流转完美闭环：面试对话 -> 生成事件 (`TaskResponse`) -> MQ (`A2ABus` 发布) -> 画像更新 (`LearningProfileAgent`) -> 永久写入 `learning_profiles_v2.json`。
4. **Agent 动态配置与降级控制**：
   - 新增 `settings.html` 与 `AgentConfigService`，支持前端一键关闭特定的 Agent (如 `EvaluationAgent`, `LearningProfileAgent`) 以节省 Token。
   - 支持通过前端动态切换各个 Agent 使用的大模型供应商（OpenAI / ZhipuAI / Ollama），由 `DynamicModelFactory` 动态分配 `ChatModel`。
5. **动态布局与扩展空间**：
   - 彻底移除了所有 HTML 页面中硬编码的 `<aside>` 侧边栏，改为通过 `layout.js` 统一动态渲染。
   - 引入 `MenuConfigService` 和 `workspace.html`（扩展空间），支持用户拖拽卡片、自定义导航栏模块的显示位置。
6. **Agent 技能按需加载与原生 Function Calling**：
   - 废除了 `RAGService` 中通过 `AgentSkillService.globalInstruction()` 全局注入所有技能的逻辑，显著节省了 LLM Context Token。
   - 改造了 `EvaluationAgent`、`DecisionLayerAgent` 等核心 Agent，使其仅在需要时通过 `resolveSkillBlock` 提取强相关的技能指令。
   - 新增了 `evaluator-optimizer` 技能，并将其应用到 `AgentEvaluationService` 中，规范了自动化评测过程中的提示词生成。
   - 实现了基于 Spring AI 的原生 Function Calling。将 `WebSearchTool` 和 `VectorSearchTool` 注册为原生的 Function Bean，彻底移除硬编码的回退逻辑，现在由大模型自主决定是否需要调用外部工具。

### 3.4 MCP 最小闭环
1. MCP 网关服务已存在（`McpGatewayService`），具备能力发现、调用重试、失败回退等。
2. 审计支持 traceId 检索。
3. 当前 MCP 仍是 stub 能力（`obsidian.write`, `code.execute`），但前后台全链路已通。

## 4. 关键文档入口（先读这些）
1. 规格：`specs/v2-platform/spec.md`
2. 任务拆解：`specs/v2-platform/tasks.md`
3. 验收清单：`specs/v2-platform/checklist.md`
4. 进展日志：`specs/v2-platform/progress.md`
5. 知识库前端规格：`specs/knowledge-ui/spec.md`
6. 智能刷题模块规格：`specs/coding-practice/spec.md` (新规划)
7. 本文：`specs/v2-platform/handover.md`

## 5. 下一步建议（按优先级）
### P1：收口 Iteration 2（真实 MCP）
1. 将 Obsidian/CodeExecution 从 stub 切到真实 MCP 服务（SSE/STDIO 已接入，建议先灰度一条主链路）。
2. 增加真实 MCP 失败场景集成测试。

### P2：开发智能刷题模块前端与对话流改造 (`practice.html`)
1. 当前后端的 `CodingPracticeAgent` 仅支持纯算法题的表单提交。需要根据 `specs/coding-practice/spec.md` 对其进行重构，增加**自然语言意图识别**，以支持对话式的多题型（算法、场景、选择、填空）生成。
2. 实现沉浸式的对话前端 `practice.html`，接入改造后的 API 接口，并确保刷题结果能闭环更新到 `LearningProfileAgent`（形成 `LearningSource.PRACTICE` 事件）。

### P3：引入轻量级嵌入式数据库（视需求而定）
1. 目前画像、配置和会话都存在项目根目录的 `.json` 文件中。随着数据量增加，全量序列化/反序列化可能遇到性能瓶颈。
2. 建议未来将 `learning_profiles_v2.json` 和 `interview_sessions.json` 平滑迁移至 **SQLite** 或 **H2**。

### P3：推进 Iteration 3（A2A 事件驱动）
1. 扩展意图：`REQUEST_CAPABILITY / CAPABILITY_RESULT / CAPABILITY_FAILED`。
2. 强化 DLQ 审计字段与批量重放统计。

## 6. 本地运行与验证
1. 编译测试：
   - `mvn test`
2. 核心手工验证建议：
   - 页面入口：浏览器访问 `http://localhost:8080/interview.html`，确认无需 Token 即可使用。
   - 知识库：访问 `http://localhost:8080/knowledge.html` 测试多路径同步与断电恢复。
   - MCP：访问 `http://localhost:8080/mcp.html`，切换左侧下拉框体验 Mock 参数自动填充与调用测试。

## 7. 风险与注意事项
1. `app.security.jwt-secret` 默认值仅供开发环境，生产必须改环境变量注入。
2. 当前 MCP 仍以 stub 为主，不能代表真实环境的稳定性。
3. JSON 文件持久化方案（如 `learning_profiles_v2.json`）在数据量达到数 MB 时可能存在读写延迟，需持续观测。

## 8. 一键接手命令清单（PowerShell）
以下命令按顺序执行即可快速接手：

```powershell
# 1) 进入项目
cd d:\Practice\InterviewReview

# 2) 查看当前变更与分支状态
git status --short
git branch --show-current

# 3) 全量回归
mvn test

# 4) 本地启动
mvn spring-boot:run
```
