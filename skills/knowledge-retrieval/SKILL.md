---
name: "knowledge-retrieval"
description: "执行关键词改写、混合检索与兜底搜索，产出证据包。用户要提升召回质量或证据覆盖时调用。"
---

# Knowledge Retrieval

## Purpose
为每轮面试生成可引用证据，支持向量检索、词法检索与兜底 web 检索。

## Invoke When
- 需要提升召回命中率或降低空结果率
- 需要构建统一的 evidence packet
- 需要把策略提示映射到检索查询

## Inputs
- 用户问题
- strategyHint 与 focusHint
- 用户画像与上下文标签

## Outputs
- 检索查询关键词
- 证据目录与片段列表
- 检索来源元信息（向量/词法/web）

## Workflow
1. 基于问题与策略生成查询词
2. 并行执行向量检索与词法检索
3. 对结果去重、重排并构建证据包
4. 结果不足时触发 web fallback

## Guardrails
- 输出必须包含可追踪来源信息
- 不在本层给出最终评分结论
- 空结果必须有明确兜底路径

## Acceptance Checks
- 常见问题可稳定返回多来源证据
- 证据包字段满足评估层引用约束
- 召回失败场景能触发 fallback
