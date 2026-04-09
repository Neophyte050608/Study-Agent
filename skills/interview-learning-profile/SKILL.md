---
name: "interview-learning-profile"
description: "维护用户面试学习画像，更新主题掌握度、薄弱项趋势和个性化提示。用于每轮评估后的画像更新、长期趋势分析、下一轮个性化出题和聚焦主题生成。"
---

# Interview Learning Profile

## Goal
把单轮评估信号沉淀为长期可复用的学习画像，支持趋势判断、个性化出题和训练建议。

## Required Input
- `userId`
- `sessionSummary`
- `scoreBreakdown`
- `evidenceSignals`
- 历史画像或历史统计（若有）

## Procedure
1. 读取已有画像并合并本轮信号；缺少历史数据时初始化基础画像。
2. 更新主题掌握度、稳定度和变化趋势，必要时考虑时间衰减。
3. 标记新出现、持续存在或明显改善的薄弱主题。
4. 输出给下游可直接消费的画像快照、趋势信号和个性化提示。

## Output Contract
- 输出至少包含 `profileSnapshot`、`weakTopics`、`trendSignals`、`personalizationHints`。
- 结果应能直接驱动 `strategyHint`、`focusHint` 或个性化出题逻辑。
- 若没有足够历史，只输出基础模板和低置信度提示，不伪造趋势。

## Guardrails
- 保持更新幂等，避免重复计算导致画像失真。
- 不暴露敏感原始文本，只保留必要特征和摘要。
- 主题能力计算口径在不同会话之间保持一致。

## Self-check
- 同一用户重复更新时，结果是否稳定且不会无故放大。
- 薄弱主题和趋势信号是否能从输入中解释出来。
- 下游系统是否可以直接消费输出字段。
