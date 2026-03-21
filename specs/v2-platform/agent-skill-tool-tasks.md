# 任务拆解 (Tasks)

## 阶段一：技能(Skill) 按需加载改造
- [ ] 审查 `AgentSkillService.java`，移除或废弃 `globalInstruction()` 方法。
- [ ] 改造 `RAGService.getChatClient()`，移除对全局技能的硬编码注入，只保留最基础的系统角色设定（"你是一位专业的中文技术面试官与复盘助手"）。
- [ ] 改造 `EvaluationAgent`，在调用评估逻辑时，通过 `AgentSkillService.resolveSkillBlock("evidence-evaluator")` 获取精准技能并作为 User Prompt 的一部分传入。
- [ ] 改造 `DecisionLayerAgent`，在制定策略时，按需注入 `"question-strategy"` 技能。

## 阶段二：工具(Tool) 原生 Function Calling 注册
- [ ] 创建 `AgentFunctionConfig.java` 配置文件。
- [ ] 在配置类中，将现有的 `WebSearchTool` 包装并注册为 `@Bean @Description("...") Function<WebSearchTool.Query, List<String>>`。
- [ ] 在配置类中，将现有的 `VectorSearchTool` 包装并注册为对应的 Function。
- [ ] 修改 `DynamicModelFactory` 或 `RAGService`，在构建 `ChatClient` 时，通过 `.defaultFunctions()` 绑定上述注册的工具。

## 阶段三：逻辑重构与测试
- [ ] 改造 `RAGService.processAnswer` 等核心方法，移除原有的硬编码 WebSearch 兜底逻辑，完全交由大模型自主通过 Function Calling 触发检索。
- [ ] 运行全量测试 `mvn test`，确保改造没有破坏原有的业务流程。