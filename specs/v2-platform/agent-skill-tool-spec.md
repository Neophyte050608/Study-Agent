# Agent 技能按需加载与工具集成架构规格 (Agent Skill On-Demand & Tool Integration Spec)

## 1. 现状问题分析
经过盘点，当前系统内共存在 8 个核心业务 Agent（如 `EvaluationAgent`, `DecisionLayerAgent`, `KnowledgeLayerAgent` 等）。
当前它们对 **Skill（技能/Prompt约束）** 和 **Tool（工具能力）** 的使用方式存在以下瓶颈：
1. **Token 浪费严重 (全局注入)**：`RAGService` 在初始化大模型客户端时，会调用 `AgentSkillService.globalInstruction()` 把**所有可用技能**的描述都塞进 System Prompt。即使当前任务不需要某些技能，也全量读取了，造成极大的 Context 浪费。
2. **缺乏真正的 Tool Calling (Function Calling)**：目前的“工具”（如 `WebSearchTool`, `VectorSearchTool`）只是后端的 Java 方法，大模型并不知道它们的存在。是由后端的 Java 代码（`RAGService`）硬编码决定何时调用工具，而不是由大模型自主规划。这违背了真正 Agentic（智能体化）的设计理念。

## 2. 架构升级目标
1. **技能按需精准加载 (Skill On-Demand)**：废除全局技能注入，改为每个 Agent 在执行时，仅提取**当前任务强相关**的 Skill 约束。
2. **解锁真实 Tool Calling**：将后端的 Java 工具注册为 Spring AI 的 `@Bean @Description` Function，让大模型可以通过原生的 Function Calling 机制主动调用，实现真正的自主推理。

## 3. 改造方案设计

### 3.1 技能(Skill) 按需加载机制重构
- **重构 `AgentSkillService`**：
  废除 `globalInstruction()` 方法。
  保留并增强 `resolveSkillBlock(String... skillNames)`。
- **Agent 改造**：
  每个 Agent 必须显式声明自己依赖的技能。
  例如 `EvaluationAgent` 只加载 `evidence-evaluator`；`DecisionLayerAgent` 只加载 `question-strategy`。
  
### 3.2 工具(Tool) 的原生 Function Calling 注册
利用 Spring AI 的 `@Bean` 和 `@Description` 注解，将现有的工具暴露给大模型。

**改造一：注册网络搜索工具**
```java
@Bean
@Description("当本地知识库无法找到相关答案时，使用此工具进行互联网搜索。")
public Function<WebSearchTool.Query, List<String>> webSearchFunction(WebSearchTool tool) {
    return tool::run;
}
```

**改造二：动态绑定 Function 到 ChatClient**
在 `DynamicModelFactory` 或 `RAGService` 构建 `ChatClient` 时，通过 `.defaultFunctions("webSearchFunction", "...")` 动态绑定。

### 3.3 预期收益
- **Token 节省**：System Prompt 大幅瘦身，只保留当前角色的核心设定，避免无关技能的污染。
- **自主性提升**：`KnowledgeLayerAgent` 可以自己决定是查本地向量库，还是查互联网，还是两者都查，后端不再需要写复杂的 if-else 路由。

## 4. 演进路线 (Phases)
- **Phase 1 (Skill 优化)**：清理 `RAGService` 中的全局系统提示词注入，改造各个 Agent，强制它们按需调用 `resolveSkillBlock`。
- **Phase 2 (Tool 注册)**：将 `WebSearchTool` 和 `VectorSearchTool` 包装为 Spring Function Bean。
- **Phase 3 (Agentic 升级)**：改造 `KnowledgeLayerAgent` 和 `RAGService` 的底层调用，开启 Function Calling。