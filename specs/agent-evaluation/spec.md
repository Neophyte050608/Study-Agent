# Agent 评估体系规格（Trial / Transcript / Scorer）

## 1. 目标
在主系统内提供一套可复用的 Agent 评估闭环，覆盖：
1. Trial：定义并执行一轮评测试跑。
2. Transcript：沉淀试跑过程的结构化轨迹。
3. Scorer：对输出按规则评分并给出可解释结果。

## 2. 范围
本阶段先落地最小可用版本：
1. 提供评估 API（创建试跑、查询试跑、查询 transcript、查询 scorer）。
2. 提供内存态 trial/transcript 存储。
3. 提供规则型 scorer 与 LLM 相关性 scorer（基于 Spring AI RelevancyEvaluator）。

暂不纳入：
1. 真实 LLM Judge 多模型仲裁。
2. 多租户持久化存储。
3. 批量并发压测编排。

## 3. 核心模型
1. Trial
   - trialId、operator、agent、task、expected、input、status、startedAt、endedAt
2. Transcript
   - turns（turn/role/content/timestamp）
3. Score
   - overall、expectedOverlap、taskAlignment、outputFormat、llmRelevancy、llmPass、scorerType、grade、rationale

## 4. API 设计
1. `POST /api/observability/agent-eval/trials`
   - 输入：`agent/task/expected/input/candidateOutput/scorerType(rule|llm|hybrid)`
   - 输出：trial 基本信息 + transcript + score
2. `GET /api/observability/agent-eval/trials`
   - 支持按 `limit/agent/status` 过滤
3. `GET /api/observability/agent-eval/trials/{trialId}`
4. `GET /api/observability/agent-eval/transcripts/{trialId}`
5. `GET /api/observability/agent-eval/scorers`

## 5. 评分策略
1. keyword-overlap（0.55）
2. task-alignment（0.30）
3. output-format（0.15）
4. llm-relevancy（0.30，按 `spring-ai-evaluation` 参考实现接入 RelevancyEvaluator）
5. 综合分转等级：A/B/C/D

## 6. 观测与审计
1. 每次创建 trial 写入运维审计动作 `AGENT_EVAL_TRIAL_RUN`。
2. 审计载荷包含 `trialId/agent` 便于追踪。

## 7. 后续演进
1. 引入 Spring AI evaluator-optimizer 形成多轮优化链路。
2. transcript 从内存迁移到 JDBC（conversation_id=trialId）。
3. 增加 scorer 插件化注册（规则型 + LLM Judge）。
