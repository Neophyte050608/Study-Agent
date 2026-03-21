# 验收清单 (Checklist)

- [ ] `AgentSkillService.globalInstruction()` 已被移除，系统启动和调用时不再把所有 `SKILL.md` 堆砌到 System Prompt 中。
- [ ] 各个 Agent（如 `EvaluationAgent`, `DecisionLayerAgent`）能够按需通过 `resolveSkillBlock` 获取自己专属的技能指令。
- [ ] 后端通过日志或监控可观测到：单次模型调用的 Input Token 消耗显著下降（由于去除了全局无关技能的注入）。
- [ ] 现有的 `WebSearchTool` 和 `VectorSearchTool` 成功注册为 Spring AI Function，且在 Swagger 或运行时能识别出其 Schema。
- [ ] 面试问答过程中，如果用户问了超纲或最新的技术问题，大模型能够**自主触发** `webSearchFunction` 获取互联网答案，而不是依靠后端 Java 代码硬编码的 fallback。