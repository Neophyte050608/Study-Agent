---
name: "question-strategy"
description: "基于难度、追问态与掌握度生成 strategyHint/focusHint。用户要优化出题策略或追问路径时调用。"
---

# Question Strategy

## Purpose
把候选人的当前表现映射为下一题策略，确保提问节奏与深度可控。

## Invoke When
- 需要根据答题质量动态调整难度
- 需要管理追问状态与下轮聚焦点
- 需要将学习画像信息注入出题逻辑

## Inputs
- 难度等级
- 追问状态
- 评估摘要与掌握度信号
- 画像中的薄弱主题

## Outputs
- strategyHint
- focusHint
- 下一轮提问约束条件

## Workflow
1. 汇总当前轮评估信号
2. 根据追问态选择策略模板
3. 结合难度与画像生成 focus
4. 产出可被知识层和评估层复用的策略字段

## Guardrails
- 不直接生成最终答案内容
- 不执行检索与评分
- 策略字段必须可解释且可序列化

## Acceptance Checks
- 策略在高/中/低掌握度下有明显差异
- 追问态切换规则前后一致
- 输出字段可被后续层直接消费
