# InterviewReview 学习系统改造进展（持续更新）

## 1. 当前阶段结论
项目已从“单一面试链路”推进到“任务路由 + 多 Agent + A2A 总线 + 统一学习画像 V2”，核心能力可运行并完成画像链路回归。

当前状态可概括为：
1. 面试主链路保持兼容（`/api/start`、`/api/answer`、`/api/report`）。
2. 通用任务调度已上线（`/api/task/dispatch`）。
3. A2A 已具备内存总线、RocketMQ 总线、幂等、回退、DLQ 基础能力。
4. 画像能力已升级为统一事件入口（面试 + 刷题双场景写回）。
5. Coding/Note 两类学习任务保持可用闭环。

---

## 2. 已完成能力清单

### 2.1 架构与路由
- 已实现 `TaskType / TaskRequest / TaskResponse` 统一任务模型。
- 已实现 `TaskRouterAgent`，支持：
  - `INTERVIEW_START / INTERVIEW_ANSWER / INTERVIEW_REPORT`
  - `LEARNING_PLAN`
  - `CODING_PRACTICE`
  - `PROFILE_EVENT_UPSERT / PROFILE_SNAPSHOT_QUERY / PROFILE_TRAINING_PLAN_QUERY`
- `InterviewService` 已改为通过 TaskRouter 分发，不再直接耦合单一编排入口。
- 已新增 `LearningProfileAgent`，统一提供 `upsertEvent / snapshot / recommend / overview / listEvents`。

### 2.2 A2A 协议与总线
- 已实现 A2A 核心对象：
  - `A2AMessage / A2AIntent / A2AStatus / A2AError`
  - `A2AMetadata / A2ATrace`
- 已实现 `A2ABus` 双实现：
  - `InMemoryA2ABus`
  - `RocketMqA2ABus`
- 已实现 trace/correlation 透传：
  - 支持 `traceId / correlationId / parentMessageId / replyTo`
- 已实现 `RETURN_RESULT` 回传链路（replyTo 场景）。

### 2.3 幂等与可靠性
- 已实现幂等存储 `A2AIdempotencyStore`：
  - 后端模式：`auto / redis / inmemory`
  - Redis 不可用自动回退内存
  - 幂等 key 结构：`prefix:yyyyMMdd:capability:source:messageId`
- 已实现 `requestReply` 超时指数退避重试（可由 context 控制超时/重试/退避）。
- 已实现 RocketMQ 发送重试，失败回退内存总线。
- 已实现消费解析失败 DLQ 转发。

### 2.4 任务能力（学习系统方向）
- `CodingPracticeAgent` 已升级为可用闭环：
  - start（生成题目）
  - submit（评估答案）
  - state（查看状态）
  - 返回 `score / feedback / nextHint / nextQuestion`
  - start 在未传 topic/profileSnapshot 时自动读取画像快照并优先使用推荐主题
  - submit 已上报 `CODING` 学习事件（topic/score/weak/familiar/evidence）
- `NoteMakingAgent` 已升级为可用闭环：
  - 生成 7 天学习计划
  - 写入 Markdown 文件并返回 `notePath`
- `InterviewOrchestratorAgent` 报告阶段已双写画像：
  - 旧路径：`InterviewLearningProfileService.recordSession`
  - 新路径：`LearningProfileAgent.upsertEvent(INTERVIEW)`

### 2.7 统一画像模型与融合策略
- 已新增统一模型：
  - `LearningSource`（`INTERVIEW` / `CODING`）
  - `LearningEvent`
  - `TrainingProfileSnapshot`
- 已实现画像融合策略：
  - source 权重（Interview > Coding）
  - 时间衰减（近期数据权重更高）
  - 输出 `weakTopicRank / familiarTopicRank / recentTrend`
- 已实现旧画像兼容迁移：
  - `learning_profiles.json` 可被读取并迁移到 `learning_profiles_v2.json`

### 2.5 Skill 体系
- 已定义 `SKILL_TEMPLATE.md` 标准模板。
- `AgentSkillService` 已实现“元信息优先 + 按需全量读取 + lastModified 缓存失效”。

### 2.6 运维与观测接口
- 已有接口：
  - `GET /api/observability/a2a/idempotency`（查看幂等状态）
  - `POST /api/observability/a2a/idempotency/clear`（清理幂等键）
  - `POST /api/observability/a2a/dlq/replay`（重放单条 DLQ）
  - `POST /api/observability/a2a/dlq/replay/batch`（批量重放 DLQ + 统计）
  - `GET /api/observability/profile/events`（画像事件查询）
  - `GET /api/profile/overview`（跨场景画像总览）
  - `GET /api/profile/recommendations?mode=interview|coding`（差异化推荐）

---

## 3. 已完成的关键配置

### 3.1 A2A 与 RocketMQ
- `app.a2a.bus.type`：`inmemory|rocketmq`
- `app.a2a.rocketmq.topic`
- `app.a2a.rocketmq.consumer-group`
- `app.a2a.rocketmq.dlq-topic`
- `app.a2a.rocketmq.publish-retries`
- `app.a2a.rocketmq.retry-backoff-ms`

### 3.2 幂等与 Redis
- `app.a2a.idempotency.backend`：`auto|redis|inmemory`
- `app.a2a.idempotency.ttl-seconds`
- `app.a2a.idempotency.max-size`
- `app.a2a.idempotency.redis-key-prefix`
- `spring.data.redis.host/port/password/timeout`

### 3.3 学习计划落盘
- `app.learning.notes.dir`

---

## 4. 测试状态
本轮新增与变更测试通过（17 tests）：
- A2A：内存总线、RocketMQ 总线、request-reply、幂等去重
- 路由：TaskRouter trace/correlation/replyTo + 画像路由
- 控制器：面试主流程、任务分发、观测接口 + 画像接口
- 画像：LearningProfileAgent 幂等/推荐策略
- 技能：懒加载与缓存失效

验证命令：
- `mvn "-Dtest=TaskRouterAgentTest,InterviewControllerFlowTest,LearningProfileAgentTest" test` 通过。
- 全量 `mvn test` 已通过（48 tests）。
- `DeepSeekTest/EvaluationTest` 上下文失败已修复（补充测试属性 `rocketmq.name-server` 与 `app.a2a.bus.type=inmemory`）。

### 4.1 关键缺陷修复前后对照
- 缺陷：刷题结果不回写统一画像  
  修复前：`CodingPracticeAgent.submit` 仅返回评估结果，不写画像。  
  修复后：submit 上报 `LearningEvent(source=CODING)`，进入统一画像融合。
- 缺陷：画像路由能力缺失  
  修复前：`TaskRouterAgent` 不支持画像任务。  
  修复后：已支持 `PROFILE_EVENT_UPSERT / PROFILE_SNAPSHOT_QUERY / PROFILE_TRAINING_PLAN_QUERY`。
- 缺陷：缺少画像总览与事件观测接口  
  修复前：无 profile overview/recommendation/events API。  
  修复后：已新增 3 个画像相关 API，并保持既有接口兼容。
- 缺陷：刷题链路未稳定命中画像驱动出题  
  修复前：CODING start 若不传 topic，固定默认主题。  
  修复后：start 可自动使用画像推荐主题，并在测试中复现“作答 -> 写画像 -> 下一题更针对”。

---

## 5. 待完成工作（建议按优先级）

### P1（建议下一迭代）
1. **DLQ 管理增强**
   - 增加 DLQ 拉取列表（按 topic、时间窗口、条数）
   - 增加重放审计日志（操作者、时间、成功率）
2. **reply 链路治理**
   - 标准化超时码与重试结果码
   - 区分可重试错误与不可重试错误
3. **幂等运维工具**
   - 按日期/前缀批量清理
   - 增加 dry-run（仅统计不删除）

### P2（学习系统能力增强）
1. CodingPractice 接入真实代码执行沙箱（编译/运行/测试用例）。
2. NoteAgent 接入 Obsidian/MCP（当前仅本地文件写入）。
3. LearningProfile 深化（趋势面板、参数化配比、topic 规范化规则）。

### P3（A2A 工程化）
1. 多实例消费顺序与并发控制策略。
2. 消息签名/鉴权与跨服务安全。
3. 全链路指标（吞吐、失败率、重放率、重复率）面板化。

---

## 6. 运行建议（当前可执行）
1. 本地开发优先 `inmemory`，保证快速迭代。
2. 联调 RocketMQ 时开启 `app.a2a.bus.type=rocketmq`。
3. Redis 密码不确定时，先用 `app.a2a.idempotency.backend=auto`，避免因连接失败阻塞主流程。
4. 每次改造后至少跑核心测试集，避免路由/消息层回归。

---

## 7. 对齐文档
- 规格文档：`specs/learning-system/spec.md`
- 任务拆解：`specs/learning-system/tasks.md`
- 验收清单：`specs/learning-system/checklist.md`
