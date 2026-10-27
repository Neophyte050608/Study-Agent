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

跨模块使用 LLM 结构化输出时，依赖
`modelrouting.application.contract.structuredoutput.StructuredOutputParser`；默认实现属于
modelrouting 内部，业务模块不得直接构造或注入实现类。结构化输出迁移不得改变调用者原有 fallback。

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

## 数据库迁移

MySQL 表结构和必要参考数据由 Flyway 管理，迁移目录为：

```text
src/main/resources/db/migration/
```

- 禁止在运行代码或 Docker 初始化目录中另建一套 schema 真相源。
- 已应用的版本化迁移不可修改；变更必须新增更高版本的 `V<version>__<description>.sql`。
- 参考数据迁移必须可重复采用已有数据库，不能覆盖用户已定制的数据。
- 数据库迁移变更至少运行 `FlywayDatabaseMigrationTest`；条件允许时还应对临时 MySQL 运行 `FlywayMySqlMigrationIntegrationTest`。

## Knowledge 与 RAG 规则

`knowledge.application.RAGService` 只保留兼容入口和主流程门面。新增逻辑按职责放置：

- 检索编排、融合、query rewrite：`knowledge.application.retrieval`
- 本地图谱链路：`knowledge.application.localgraph`
- Knowledge QA 的 Agent、Handler 与上下文：`knowledge.application.agent`、`knowledge.application.context`
- 通用聊天流式编排：`chat.application`；知识问答场景 Handler：`knowledge.application.chat`
- 刷题生成、评估、兜底：`practice.application`
- 面试答案评分：`interview.application.evaluation`
- 通用 trace runtime、事件和 operational query：`observability.application.trace`、`observability.application.ObservabilityQueryService`
- metrics snapshot 聚合：`observability.application.metrics`
- 显式用户反馈及其聚合契约：`feedback.application`、`feedback.application.contract`
- 知识库和文档管理：`knowledge.application.catalog`

不要把新职责继续加到 `knowledge.application` 根包，也不要重建已退休的 `knowledge.application.coding` 或 `knowledge.application.observability`。跨模块只能调用 provider 的 `application.contract`，不得直接访问其他模块的 DO、Mapper 或具体 application service。

## Agent Runtime 预留边界

成熟框架引入顺序和边界见 `docs/architecture/framework-adoption-roadmap.md`。

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

## 固定 DAG 学习计划预览

`LEARNING_PLAN_PREVIEW` 的职责、调用入口和边界见 `learning-plan-preview.md`。Learning 定义只读三节点，Knowledge 实现 Learning-owned 窄扩展，Agent 提供有界调度。不要复用旧笔记生成链，也不要在本步接入工具或自动重试。新增图执行行为要覆盖入度等待、降级、队列/请求过载、中断与上下文清理，并运行 ArchUnit。

持久预览入口的 task/node/attempt 状态归 Agent，Learning 通过 Agent-owned workflow SPI 提供节点和 codec。数据库事务只用于领取、检查点和终态，不跨模型调用。新增持久图行为必须覆盖失效 epoch 拒写、期限/attempt budget、成功 seed 复用、重试后继闭包与真实取消容量；不得用 mock 替代 MySQL fencing 证据。

持久预览 HTTP 位于 `learning.api`，Controller只调用Learning/Identity公开契约；结果JSON解码在Learning application，HTTP字段通过API-owned `LearningPlanTaskResponse`映射，不直接返回运行时或application记录。局部错误advice优先级高于全局处理，不返回原始异常内容。测试应加载真实SecurityFilterChain与JWT decoder，覆盖认证、owner替换、跨owner和open单用户语义，不能仅靠MockMvc设置principal声称JWT认证已验证。
