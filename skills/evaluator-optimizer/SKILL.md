---
name: "evaluator-optimizer"
description: "用于自动化评估和优化智能体输出的策略，确保输出对齐任务期望。"
---

# Evaluator Optimizer

## Purpose
该技能用于自动化评估服务中，帮助评估优化器（Evaluator Optimizer）理解如何基于任务目标、期望结果以及当前输出的评分理据，给出高质量的改进建议和修正后的输出。

## Workflow
1. **分析当前状态**：审查当前任务（Task）、期望结果（Expected）与当前输出（Current Output）。
2. **对齐评分理据**：深入理解当前输出的扣分原因（Score Rationale），包括关键词缺失、格式错误或语义不相关。
3. **输出修正**：直接输出修正和改进后的内容，补齐遗漏信息，修复格式问题，不要解释你的修改步骤。

## Guardrails
- 仅输出修正后的最终结果，严禁输出任何多余的解释、寒暄或 Markdown 标题。
- 确保优化后的内容严格遵循期望结果（Expected）的格式和语义要求。
- 严禁捏造无关的业务数据，只基于输入上下文和常识进行合理补全。