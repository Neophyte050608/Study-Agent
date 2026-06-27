# 后端开发规范

本文档补充 `ARCHITECTURE.md` 和 `PACKAGE_CONVENTIONS.md`，用于指导 Spring Boot 后端开发。

## 分层与包结构

后端根包：

```text
io.github.imzmq.interview
```

新增业务代码采用 domain-first：

```text
<domain>.api              # Controller、request/response DTO
<domain>.application      # use case 编排、应用服务、应用内部 DTO
<domain>.domain           # 领域对象、值对象、纯业务规则
<domain>.infrastructure   # 持久化、外部系统、技术适配器
```

不要新增业务代码到顶层 `service`、`entity`、`mapper`、`dto` 包。这些是历史类型分层目录，不再作为新代码入口。

## API 层

Controller 只负责：

- 参数接收和基本校验。
- 调用 application service。
- 将结果转换为 HTTP response。

Controller 禁止：

- 直接调用 MyBatis Mapper。
- 编写复杂业务流程。
- 直接处理外部服务协议细节。

## Application 层

Application service 负责用例编排：

- 调用领域规则。
- 协调持久化 adapter、模型调用、检索服务、消息发送等。
- 处理事务边界和降级策略。

复杂流程应拆成小的协作类，不要把所有逻辑塞进一个 service。

## Domain 层

Domain 层放稳定业务概念：

- 实体和值对象。
- 纯业务判断。
- 与框架无关的策略。

Domain 层不依赖 Spring MVC、MyBatis DO、Controller DTO 或外部 API client。

## Infrastructure 层

MyBatis 相关类放在：

```text
<domain>.infrastructure.persistence
```

外部系统适配器放在：

```text
<domain>.infrastructure.<adapter-name>
```

例如模型服务、Milvus、Neo4j、Feishu/QQ、MCP 等集成，都应封装在明确 adapter/client 后，不要散落在业务流程中。

## Knowledge 与 RAG 规则

`knowledge.application.RAGService` 只保留兼容入口和主流程门面。新增逻辑按职责放置：

- 检索编排、融合、query rewrite：`knowledge.application.retrieval`
- 本地图谱链路：`knowledge.application.localgraph`
- 多轮上下文策略：`knowledge.application.context`
- 流式处理：`knowledge.application.chatstream`
- 刷题生成、评估、兜底：`knowledge.application.coding`
- 评测、答案评分、证据校验：`knowledge.application.evaluation`
- trace、事件、观测：`knowledge.application.observability`
- 知识库和文档管理：`knowledge.application.catalog`

不要把新职责继续加到 `knowledge.application` 根包。

## Agent Runtime 预留边界

当前项目仍以内置 Spring Boot agent 编排为主，未来可能引入 Mastra 或独立 Agent Runtime。为降低迁移成本：

- Agent 能力通过明确 application service 或 adapter 暴露。
- 不要让 Controller 直接依赖具体 agent 实现。
- 工具调用、技能执行、模型路由应保持可替换。
- 会影响 durable state 的 agent 行为必须回到后端 application/persistence 处理。

## 配置与密钥

- 本地私有配置放 `application-private.yml` 或 `.env`，不要提交。
- 新增配置项时提供安全默认值或文档说明。
- 不要在日志中输出 token、API key、cookie、Authorization header。

## 测试要求

- 行为变更需要对应单元测试或集成测试。
- 包结构和依赖方向变化需要运行 `ArchitectureRulesTest`。
- 避免依赖真实模型、真实外部 API 和个人本地服务；必要时使用 stub 或 mock。
- 测试命名以被测类或行为为中心，例如 `KnowledgeRetrievalCoordinatorTest`。
