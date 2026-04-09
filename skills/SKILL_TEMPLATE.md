---
name: "skill-name"
description: "说明该技能做什么，以及在什么场景下使用该技能。description 需要同时覆盖功能范围和触发条件。"
---

# Skill Name

## Goal
用一句话说明该技能要帮助另一个 agent 完成什么任务。

## Required Input
- 列出完成任务所需的最小输入
- 缺失时允许的保守退化方式

## Procedure
1. 按顺序写出执行步骤，使用祈使句或动词开头。
2. 只保留对 agent 真正有帮助的流程约束。
3. 如果有结构化输出要求，在流程中明确说明。

## Output Contract
- 写明必须输出什么
- 写明输出格式、字段或标签约束
- 写明哪些内容绝对不能输出

## Guardrails
- 写明边界条件、禁止事项和工具使用约束
- 避免把“何时使用”写在正文里，触发条件应优先写进 frontmatter 的 `description`

## Self-check
- 输出前检查格式是否可解析
- 检查是否遗漏必填字段或关键约束
- 检查是否引入了原任务中不存在的假设
