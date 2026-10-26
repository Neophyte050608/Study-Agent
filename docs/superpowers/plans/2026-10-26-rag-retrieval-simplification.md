# RAG 检索精简实施计划

> 按 executing-plans / TDD 在当前会话直接执行；用户明确不要子代理。代码在 codex/rag-retrieval-simplification 分支，保留用户现有未提交文件，不自动 commit/push。

设计依据：`docs/superpowers/specs/2026-10-26-rag-retrieval-simplification-design.md`。

## Task 1：行为基线

扩展 RAGServiceTest：通道异常隔离、两路去重、空 Web fallback 替换、trace root/child。运行 RAGServiceTest、ParentChildRetrievalHydrationTest、RetrievalEvaluationServiceTest、KnowledgeApplicationContractAdapterTest，确认当前实现基线。

## Task 2：提取检索内核（RED → GREEN）

先新增三类直接单元测试，以及 ArchitectureRulesTest 约束 RAGService 不访问检索基础设施、Builder 不依赖 RetrievalDelegate。运行并确认新测试因缺失实现/旧依赖失败。

依次迁移：
1. EvidenceFusionService：原 RRF/重排/分数算法原样移动。
2. GroundingContextAssembler：原父子回填、证据/图片/文档摘要规则移动；增加上下文 join 方法。
3. HybridEvidenceRetriever：原三路并发和图谱摘要移动；明确组合顺序。
4. Builder 直接注入组件及 RagTraceOperations，删除 delegate；RAGService 构造器移除检索依赖，仅委托 Builder。
5. 同步两个现有测试的真实组件装配；新增轻量 Spring 装配测试防止 bean 缺失/循环依赖。

运行 focused tests 与 ArchUnit，保持旧断言不降低。

## Task 3：文档与回归

更新 PACKAGE_CONVENTIONS（ownership 唯一细则）及 ARCHITECTURE 中实际检索依赖链。运行 mvn compile、test-compile、全量 test、ArchitectureRulesTest、verify -DskipTests、git diff --check。检查 diff 与 git status：不得覆盖 AGENTS.md / .DS_Store，不提交或推送。报告本轮真实完成范围及明确延后事项。
