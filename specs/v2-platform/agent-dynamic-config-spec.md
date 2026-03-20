# 动态 Agent 配置与降级控制设计规格 (Dynamic Agent Config Spec)

## 1. 业务目标
1. **动态模型路由**：支持在前端按 Agent 粒度（如：评估 Agent、决策 Agent、画像 Agent）动态配置其使用的 AI 供应商（OpenAI/Zhipu/Ollama）、API Key 以及具体模型版本。
2. **动态启停与降级**：支持在前端一键关闭某个 Agent。关闭后，系统将绕过该 Agent 的大模型调用，执行默认的降级逻辑，从而节省 Token 消耗并提升响应速度。

## 2. 核心领域模型定义

系统需要引入一个 `AgentConfig` 配置实体，用于持久化每个 Agent 的设定。

```json
// agent_configs.json 示例
{
  "EvaluationAgent": {
    "enabled": true,
    "provider": "OPENAI",
    "apiKey": "sk-xxxx...",
    "modelName": "gpt-4o",
    "temperature": 0.3
  },
  "LearningProfileAgent": {
    "enabled": false, // 关闭画像更新以节省 Token
    "provider": "ZHIPU",
    "apiKey": "xxxx.yyyy",
    "modelName": "glm-4-flash",
    "temperature": 0.7
  }
}
```

## 3. 后端架构改造方案

### 3.1 配置管理层 (`AgentConfigService`)
- 负责读取和写入根目录下的 `agent_configs.json`。
- 提供内存级缓存，避免每次读取文件。并在配置变更时触发事件更新。

### 3.2 动态模型路由层 (`DynamicModelFactory`)
- **现状**：目前各个 Agent 都是通过 `@Autowired` 或构造函数直接注入 Spring Boot 自动装配的 `@Primary ChatModel`。
- **改造**：引入 `DynamicModelFactory`。当 Agent 需要调用大模型时，不再直接使用全局的 `ChatModel`，而是向工厂请求：`ChatModel model = factory.getForAgent("EvaluationAgent")`。
- **动态实例化**：工厂会根据 `AgentConfig` 里的 `provider` 和 `apiKey`，在内存中动态 new 出对应的 `OpenAiChatModel` 或 `ZhipuAiChatModel` 实例，并使用 `ConcurrentHashMap` 进行缓存。配置变更时自动清理旧实例。

### 3.3 Agent 降级与短路逻辑 (Circuit Breaker)
在每个 Agent 的核心执行方法入口，增加 `enabled` 判断：
- **DecisionLayerAgent**：如果关闭，则退化为简单的轮询出题，不再通过大模型做复杂规划。
- **LearningProfileAgent**：如果关闭，则直接跳过画像更新，MQ 消费者直接 Ack 成功。
- **EvaluationAgent**：如果关闭，则给所有回答默认返回“基础合格”，或直接返回前端“评估已关闭”。

## 4. 前端页面改造方案

### 4.1 全局设置页面 (`settings.html`)
在左侧边栏底部的“设置”菜单中，新增一个独立页面或弹窗。
- **UI 布局**：采用卡片列表（Card List）布局，每个卡片代表一个 Agent。
- **交互元素**：
  - **启用开关 (Toggle Switch)**：控制 Agent 是否工作。
  - **供应商选择 (Select)**：下拉框选择 OpenAI / 智谱 / Ollama 本地模型。
  - **API Key (Input-Password)**：密码框，如果留空则默认使用后端的全局环境变量。
  - **模型名称 (Input)**：如 `gpt-4o-mini`, `glm-4`。

### 4.2 接口支持
- `GET /api/settings/agents`：获取所有 Agent 的当前配置。
- `POST /api/settings/agents/{agentId}`：保存或更新单个 Agent 的配置。

## 5. 演进路线 (Implementation Steps)

1. **Phase 1**：创建 `AgentConfigService` 和 `agent_configs.json`，实现基础的配置增删改查。
2. **Phase 2**：实现 `DynamicModelFactory`，并挑选一个非核心 Agent（如 `LearningProfileAgent`）进行重构，跑通动态 Key 和动态模型的链路。
3. **Phase 3**：在所有 Agent 中接入 `enabled` 短路判断，实现降级逻辑。
4. **Phase 4**：开发前端 `settings.html` 页面，完成前后端联调。
