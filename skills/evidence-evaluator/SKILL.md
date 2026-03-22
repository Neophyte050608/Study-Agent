---
name: "evidence-evaluator"
description: "基于证据包做结构化评分并校验引用合法性。用户要实现可解释评估与扣分依据时调用。"
---

# Evidence Evaluator

## Purpose
将回答质量转化为可解释评估结果，输出维度分、扣分项、引用与冲突信息。并具备自我反思（Self-RAG）能力，确保评价严格遵循知识库证据。

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
1. **反思评估**：首先在内心反思（Self-RAG）：知识库证据是否足以支撑评价？如果不足，降低苛刻度；如果充足，严格对照。
2. **加载策略与证据上下文**
3. **对回答进行维度评估与扣分定位**
4. **生成可溯源 citations/conflicts**
5. **校验引用是否在证据目录内**

## Guardrails
- 不使用证据目录外的引用
- 不在输出中泄露隐私与敏感信息
- 评分尺度在轮次间保持一致

## Acceptance Checks
- 评估结果包含分数、理由、引用。
- 必须确保所有要求的字段（尤其是 nextQuestion 等必填字段）均存在且不为空。
- 非法引用会被拒绝或修正。
- 输出可直接用于成长反馈生成
