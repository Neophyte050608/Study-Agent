---
name: "interview-growth-coach"
description: "把多轮面试得分趋势和错误模式转成个性化训练建议与阶段计划。用于评分结束后的成长反馈、下一步训练路径设计、阶段性复盘和下轮模拟聚焦。"
---

# Interview Growth Coach

## Goal
把多轮评估结果转成可执行的成长计划，形成“问题识别 -> 训练动作 -> 验收标准 -> 下轮聚焦”的闭环。

## Required Input
- `scoreTimeline`
- `errorPatterns`
- `targetRole`
- `availableTime`（可选）
- 其他能说明近期表现的上下文

## Procedure
1. 聚合历史分数、维度分和扣分理由，识别高频且高影响的问题。
2. 将问题映射为训练主题，按优先级排序，避免一次处理过多弱项。
3. 为每个优先弱项设计训练动作、练习频率和复盘方式。
4. 输出周计划、微训练任务、成功标准和下次模拟重点。
5. 数据不足时退化为通用但可执行的训练模板，不伪造趋势。

## Output Contract
- 输出至少包含 `priorityWeaknesses`、`weeklyPlan`、`microDrills`、`successCriteria`、`nextInterviewFocus`。
- 每个弱项都要对应具体动作和明确完成判据。
- 计划负载应符合用户可投入时间；未提供时间时默认给出中等负载方案。

## Guardrails
- 建议必须具体、可执行、可度量、可复盘。
- 优先修复高频且高影响的问题，不平均分配注意力。
- 避免过度计划；单周计划要能实际完成。

## Self-check
- 是否明确区分了“问题是什么”和“接下来做什么”。
- 每个训练动作是否能在下轮评估中被验证。
- 建议量是否与 `availableTime` 匹配，没有堆砌任务。
