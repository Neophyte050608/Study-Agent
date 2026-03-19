---
name: "evidence-evaluator"
description: "基于证据包做结构化评分并校验引用合法性。用户要实现可解释评估与扣分依据时调用。"
---

# Evidence Evaluator

## Purpose
将回答质量转化为可解释评估结果，输出维度分、扣分项、引用与冲突信息。

## Invoke When
- 需要可解释评分与结构化评语
- 需要限制 citations/conflicts 仅引用证据目录
- 需要把策略信号纳入评分上下文

## Inputs
- 用户回答
- strategyHint 与 focusHint
- knowledge packet（含证据目录）

## Outputs
- 总分与维度分
- 扣分依据与改进建议
- citations 与 conflicts

## Workflow
1. 加载策略与证据上下文
2. 对回答进行维度评估与扣分定位
3. 生成可溯源 citations/conflicts
4. 校验引用是否在证据目录内

## Guardrails
- 不使用证据目录外的引用
- 不在输出中泄露隐私与敏感信息
- 评分尺度在轮次间保持一致

## Acceptance Checks
- 评估结果包含分数、理由、引用
- 非法引用会被拒绝或修正
- 输出可直接用于成长反馈生成
