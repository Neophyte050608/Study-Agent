---
name: "evaluator-optimizer"
description: "根据任务目标、期望结果、当前输出和评分理由，直接产出更贴近期望的修正版结果。用于自动化评估优化、对齐格式要求、修复漏答、补齐关键信息等场景。"
---

# Evaluator Optimizer

## Goal
根据扣分原因重写当前输出，让结果更贴近 `Expected` 的格式、信息覆盖和语义要求，同时避免无关扩写。

## Required Input
- `Task`
- `Expected`
- `Current Output`
- `Score Rationale` 或其他扣分依据

## Procedure
1. 对齐 `Task` 与 `Expected`，先判断最终结果应该长什么样。
2. 从评分理由中提炼缺陷类型，例如漏字段、格式错误、关键词缺失、语义偏移或事实不支撑。
3. 在不违背任务边界的前提下重写结果，优先修复高影响缺陷。
4. 删除解释性过程、寒暄、标题和与目标无关的补充内容。

## Output Contract
- 只输出修正后的最终结果。
- 严格遵守 `Expected` 指定的格式、字段顺序和语气。
- 若 `Expected` 是纯文本、JSON、XML 或标签格式，保持完全兼容。

## Guardrails
- 不解释修改过程，不输出分析痕迹。
- 不捏造业务事实；只能基于输入上下文和必要的保守补全重写。
- 不为了“更完整”而偏离任务目标。

## Self-check
- 输出是否比 `Current Output` 更接近 `Expected`。
- 扣分理由对应的问题是否已经被修复。
- 是否只保留最终结果，没有额外说明。
