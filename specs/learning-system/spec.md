# InterviewReview 学习系统化改造规格（V1.1）

## 1. 目标调整
你新增的核心目标是：让“面试 + 刷题”共享同一学习画像，并能持续针对薄弱点训练。

本次规格更新回答两个问题：
1. 是否需要新增一个 Agent？
2. 现有计划如何重写，并明确未完成/有缺陷部分？

---

## 2. 是否需要新增 Agent（决策）
### 结论
需要新增一个**独立的画像代理层**，建议命名 `LearningProfileAgent`（或 `ProfileOrchestratorAgent`）。

### 原因
1. 现状中画像更新主要由面试报告阶段驱动，刷题更新不完整，导致画像偏科。
2. 面试和刷题都需要“读画像 + 写画像 + 训练建议”，如果分散到各 Agent，会重复逻辑并产生不一致。
3. 独立画像 Agent 可沉淀统一规则：权重融合、薄弱点提取、熟悉点提取、训练计划生成。

### 职责边界
1. **统一写入**：接收面试事件、刷题事件，更新全局画像。
2. **统一读取**：提供画像快照、薄弱点排行、熟悉点排行。
3. **统一策略**：输出下一轮训练建议（面试/刷题模式）。

---

## 3. 当前实现状态（真实进度）
### 已完成
1. `TaskRouterAgent` + 统一任务模型已可用。
2. A2A 协议与总线（内存 + RocketMQ）已可用。
3. `CodingPracticeAgent`、`NoteMakingAgent` 已有最小可用流。
4. 技能规范与懒加载已落地。
5. 幂等、request-reply、DLQ 单条/批量重放、幂等清理接口已落地。

### 未完成或有缺陷（重点）
1. **画像写入不统一**：面试与刷题未完全走同一画像写入入口。
2. **跨场景权重缺失**：没有“面试权重 + 刷题权重”的融合策略。
3. **薄弱点标签标准缺失**：不同链路提取口径不一致（topic、weakPoint、blindSpot 未标准化）。
4. **画像驱动训练不足**：出题/追问还没有稳定命中“弱项优先 + 熟项巩固”配比策略。
5. **观测闭环不完整**：缺少画像更新审计与趋势可视化 API。

---

## 4. 新架构（V1.1）
### 4.1 调度层
1. `TaskRouterAgent` 继续作为统一入口。
2. 新增 `LearningProfileAgent`，由 Router 调用或由事件驱动订阅调用。

### 4.2 协作层（A2A）
新增画像事件类型：
1. `PROFILE_EVENT_UPSERT`
2. `PROFILE_SNAPSHOT_QUERY`
3. `PROFILE_TRAINING_PLAN_QUERY`

### 4.3 能力层
1. Interview 执行器（现有）
2. Coding 执行器（现有）
3. LearningProfile 执行器（新增）
4. Note 执行器（现有）

---

## 5. 统一数据模型（新增）
新增 `LearningEvent`（建议）：
1. `eventId`
2. `userId`
3. `source`（INTERVIEW / CODING）
4. `topic`
5. `score`
6. `weakPoints`
7. `familiarPoints`
8. `evidence`
9. `timestamp`

新增 `TrainingProfileSnapshot`（建议）：
1. `weakTopicRank`
2. `familiarTopicRank`
3. `recentTrend`
4. `recommendedNextInterviewTopics`
5. `recommendedNextCodingTopics`

---

## 6. API 规划更新
保留：
1. `/api/start` `/api/answer` `/api/report`
2. `/api/task/dispatch`

新增建议：
1. `GET /api/profile/overview`（跨场景画像总览）
2. `GET /api/profile/recommendations?mode=interview|coding`
3. `GET /api/observability/profile/events?limit=...`

---

## 7. 迁移与兼容策略
1. 保留现有 `InterviewLearningProfileService` 作为底层存储入口。
2. 先新增 `LearningProfileAgent` + 事件模型，不立即删除旧方法。
3. 先让 Interview/Coding 双写（旧路径 + 新路径），稳定后切换为新路径单写。
4. 对历史画像文件做兼容读取，缺失字段用默认值补齐。

---

## 8. 风险与缓解（更新）
1. 风险：双写期间数据重复  
   缓解：`eventId` + 幂等去重。
2. 风险：topic 口径不一致  
   缓解：新增 topic normalize 规则层。
3. 风险：刷题对画像影响过大  
   缓解：引入 source 权重与时间衰减。

---

## 9. 里程碑（重写）
1. M1：`LearningProfileAgent` 骨架 + `LearningEvent` 模型 + 统一写入入口。
2. M2：Interview/Coding 都接入画像事件上报，形成双场景写回闭环。
3. M3：画像驱动训练策略上线（弱项优先 + 熟项巩固）。
4. M4：画像观测 API 与趋势面板化。

---

## 10. 本轮验收标准
1. 面试与刷题都能写入统一画像入口。
2. 画像快照能同时体现弱项与熟项排行。
3. 训练建议能按模式（interview/coding）差异化输出。
4. 现有面试接口完全兼容，不破坏已有流程。
