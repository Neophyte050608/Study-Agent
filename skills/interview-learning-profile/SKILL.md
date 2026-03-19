---
name: "interview-learning-profile"
description: "维护用户学习画像并输出薄弱主题趋势。Invoke when updating profile after rounds or when user asks personalized interview focus."
---

# Interview Learning Profile

## Purpose
维护用户在多轮模拟中的能力画像，沉淀主题掌握度、薄弱项趋势与个性化建议依据。

## Invoke When
- 每轮评估完成后需要更新用户画像
- 需要为下一轮出题提供个性化聚焦主题
- 用户要求查看长期进步趋势与能力短板

## Inputs
- userId（用户标识）
- sessionSummary（本轮会话摘要）
- scoreBreakdown（维度分与主题分）
- evidenceSignals（引用与冲突信号）

## Outputs
- profileSnapshot（当前画像快照）
- weakTopics（薄弱主题列表）
- trendSignals（能力趋势信号）
- personalizationHints（个性化提问提示）

## Workflow
1. 读取用户历史画像并合并本轮信号
2. 更新主题能力分、稳定度与衰减权重
3. 识别新出现或持续存在的薄弱点
4. 输出给决策层和知识层可消费的提示

## Guardrails
- 画像更新需保持幂等，避免重复写入放大
- 不暴露敏感原始文本，仅保留必要特征
- 主题能力计算规则在不同会话保持一致

## Acceptance Checks
- 同一用户多轮后可观察到主题趋势变化
- 输出字段可直接驱动 strategyHint/focusHint
- 历史数据不足时仍能返回基础画像模板
