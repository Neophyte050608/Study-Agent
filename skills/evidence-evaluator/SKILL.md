---
name: "evidence-evaluator"
description: "基于证据包对回答做结构化评分、生成扣分依据并校验引用合法性。用于可解释评估、证据约束评分、输出 citations/conflicts、限制引用范围到给定证据目录等场景。"
---

# Evidence Evaluator

## Goal
在证据约束下完成可解释评分，让分数、理由、引用与冲突信息可以被后续模块直接消费。

## Required Input
- 用户回答
- `strategyHint` 与 `focusHint`（若有）
- knowledge packet，至少包含证据目录和可引用片段
- 调用方规定的输出 schema（若有）

## Procedure
1. 先判断证据是否足以支撑严格评分；证据不足时降低断言强度，不凭空补证。
2. 结合策略提示明确本轮更关注哪些维度，再评估回答质量。
3. 逐项定位得分点和扣分点，确保每条关键判断都能追溯到证据或明确标记为缺失。
4. 生成 `citations` 与 `conflicts`，并逐条校验是否都来自证据目录。
5. 按调用方 schema 输出总分、维度分、理由、改进建议及必填字段。

## Output Contract
- 输出至少包含总分、维度分、扣分依据、改进建议、`citations`、`conflicts`。
- 若调用方要求 `nextQuestion` 等字段，必须保留且不得为空。
- 所有引用都必须能在证据目录中找到对应项。

## Guardrails
- 不使用证据目录外的引用或知识冒充证据。
- 不泄露隐私、敏感文本或不必要的原始片段。
- 跨轮次保持评分尺度一致；证据变化时只调整结论，不随意漂移标准。

## Self-check
- 每个高影响判断是否都有证据支撑。
- `citations` 与 `conflicts` 是否全部合法且可追溯。
- 必填字段是否完整、非空、格式正确。
- 在证据不足时是否降低了断言强度而不是硬判。
