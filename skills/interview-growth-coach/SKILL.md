---
name: "interview-growth-coach"
description: "Generates personalized drill plans from score trends and mistakes. Invoke after scoring rounds or when user asks for next-step training."
---

# Interview Growth Coach

## Purpose
将多轮评估结果转成个性化训练计划，形成“问题识别 → 训练动作 → 下轮聚焦”的成长闭环。

## Invoke When
- 评估层输出完成后，需要生成针对性改进建议
- 用户询问下一步训练路径或复盘方案
- 会话结束时需要产出阶段性成长报告

## Inputs
- scoreTimeline（历史分数时间线）
- errorPatterns（高频错误模式）
- targetRole（目标岗位）
- availableTime（可投入训练时间，可选）

## Outputs
- priorityWeaknesses（优先改进项）
- weeklyPlan（周训练计划）
- microDrills（微训练任务）
- successCriteria（验收标准）
- nextInterviewFocus（下次模拟重点）

## Workflow
1. 聚合各轮评分与扣分依据
2. 识别高频高影响弱项并排序
3. 生成可执行的周计划与微训练任务
4. 输出可量化验收标准与下轮聚焦点

## Guardrails
- 建议必须可执行、可度量、可复盘
- 优先处理高频且高影响的问题
- 控制训练负载，避免过度计划

## Acceptance Checks
- 输出包含弱项优先级、计划、验收标准
- 同一弱项具备明确训练动作与完成判据
- 数据不足时提供可落地的通用训练模板
