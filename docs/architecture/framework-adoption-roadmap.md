# 成熟框架引入路线图

本文档记录 Study-Agent 后续可考虑引入的成熟框架，以及它们与当前自研模块的关系。目标不是追逐完整 ML Infra 技术栈，而是降低当前 AI 应用、RAG、Agent 和本地知识系统的自研复杂度。

## 项目定位

Study-Agent 当前更接近：

```text
AI 应用 + RAG + Agent 编排 + 本地知识系统 + 学习/评测闭环
```

因此优先关注：

- LLM、RAG、Agent 调用链可观测性。
- Prompt、模型调用、检索证据和评测结果的追踪。
- 长任务、异步任务和 Agent action 的可恢复执行。
- 工具调用的权限、审计和安全边界。
- 检索质量和模型选择策略的可比较、可回滚。

暂时不把训练平台、分布式训练、Kubernetes 机器学习平台作为主线。

## 当前模块与候选框架

| 当前区域 | 当前状态 | 可考虑框架/项目 | 建议 |
| --- | --- | --- | --- |
| `observability`、RAG trace、模型调用记录 | 已有自研 trace 和前端观测页面 | Langfuse、OpenTelemetry | 优先增强，不要一次性重写 |
| `chat.application.PromptManager`、prompt 模板 | 自研 prompt 管理 | Langfuse Prompt Management | 先做 adapter，逐步迁移核心 prompt |
| `knowledge.application.retrieval`、RAG pipeline | Spring AI + Milvus + 自研融合 | Spring AI、Milvus Hybrid Search、Langfuse trace | 保留现有主链路，增强可观测和检索策略 |
| `agent.*`、`skill.*` | Spring Boot 内部 Agent/Skill 编排 | Mastra、MCP、Temporal | 先抽象 runtime 边界，后续再外置 |
| `ingestion.application`、`ingestion.pipeline` | 自研同步/入库 pipeline | Temporal | 第二阶段引入，承接长任务和重试 |
| `eval-service`、`resources/eval` | Ragas 评测 + 本地数据集 | Langfuse Evaluations、MLflow | 先 Langfuse，MLflow 后置 |
| `modelrouting` | 自研模型候选、健康探测、熔断 | Spring AI + OpenTelemetry + Langfuse | 保留自研路由策略，补 trace/cost/latency |
| `media`、图片向量检索 | CLIP 服务 + Milvus | Milvus 多向量/混合检索 | 不换库，优先用好现有 Milvus |

## 推荐阶段

### Phase 1：可观测性与评测闭环

推荐顺序：

```text
Langfuse -> OpenTelemetry -> Spring AI 边界整理
```

目标：

- 每次模型调用可看到 provider、model、prompt、latency、token、失败原因。
- 每次 RAG 可看到 query rewrite、召回证据、重排结果、最终引用。
- Prompt 变更可以版本化，并能和评测结果关联。
- 系统级 HTTP、数据库、Redis、外部模型调用可以串成 trace。

落地原则：

- 先在 `observability` 增加 adapter，不让业务代码直接依赖 Langfuse SDK。
- 第一步实施切片已落地 `AiObservationPublisher`、`sanitizeForExternalObservation(...)` 和 `docs/development/observability-guidelines.md`，后续 Langfuse/OpenTelemetry 作为 adapter 接入。
- RAG 和模型路由只发事件，不关心事件最终写到哪里。
- 保留当前前端观测页面，必要时从 Langfuse/OpenTelemetry 聚合数据。

### Phase 2：长任务与 Agent 动手干活

推荐顺序：

```text
Temporal -> Mastra -> MCP Tool Registry / Permission Policy
```

目标：

- 知识库同步、图片摘要、批量评测、学习画像更新具备可重试、可恢复、可审计能力。
- Agent action 不再只是一次性方法调用，而是有状态、有权限、有执行记录的任务。
- Spring Boot 继续负责 durable state、权限、用户、知识库和审计。
- Mastra 或其他 Agent Runtime 只负责 planning、tool calling、workflow runtime，不直接拥有核心业务数据。

落地原则：

- 先定义 `agent runtime port` 和 `tool execution port`。
- 不让 Controller 直接调用具体 Agent Runtime。
- 会改变数据库状态的 action 必须回到 Spring Boot application 层执行。
- Temporal workflow 不直接塞复杂业务规则，业务规则仍归 application/domain。

### Phase 3：模型、检索与实验工程化

推荐顺序：

```text
Milvus Hybrid Search -> MLflow -> vLLM / TGI
```

目标：

- Dense、sparse、图谱、关键词、图片召回策略可以做稳定对比。
- 评测数据集、实验结果和模型/检索参数可追踪。
- 当确实需要本地 GPU 推理时，再把模型服务化。

落地原则：

- Milvus 继续作为主向量库，不因框架流行而迁移到其他向量库。
- MLflow 用于实验和模型/策略版本管理，不替代业务数据库。
- vLLM/TGI 仅在有稳定本地模型推理需求时引入。

## 暂不推荐引入

以下技术暂不作为当前主线：

- PyTorch、DeepSpeed、Megatron-LM、LoRA、PEFT：当前项目不以训练/微调大模型为主。
- Kubeflow、KServe、Seldon Core：当前没有多租户机器学习平台和大规模 K8s 模型部署需求。
- Feast：当前没有独立在线/离线特征平台需求。
- Spark、Flink、Dask：当前数据规模和处理模式暂不需要大数据计算框架。
- Triton、TensorRT：当前重点不是自建高性能 GPU 推理服务。
- Istio、完整服务网格：当前服务数量和治理复杂度不足以抵消引入成本。

## 引入原则

1. 先解决现有痛点，再引入框架。
2. 每次只引入一个主框架，避免同时改变观测、工作流和 Agent Runtime。
3. 所有外部框架必须通过 adapter/port 隔离，业务层不直接依赖框架 SDK。
4. 引入前先定义退出策略：如果框架不用了，哪些代码需要删除，哪些数据要迁移。
5. 不替换已有稳定能力，除非新框架能明显降低维护成本。
6. 任何会影响启动方式、依赖服务、配置项或开发流程的引入，都必须同步 `README.md`、`AGENTS.md` 和 `docs/development/`。

## 推荐决策顺序

当前建议的长期顺序：

```text
1. Langfuse：LLM/RAG/Prompt/Agent trace 和评测闭环
2. OpenTelemetry：系统级 trace、metrics、logs 统一入口
3. Temporal：知识入库、评测、Agent action 等长任务
4. Mastra：复杂 Agent Runtime 和工具编排
5. MLflow：实验追踪、模型/检索策略版本管理
6. vLLM / TGI：稳定本地模型推理服务化
```

短期不再以引入新工作流框架为前提：2026-10-27 先落地内置 Java 固定 DAG 的只读学习计划预览，验证有界并行、依赖终止与失败降级。随后单独设计 Agent-owned MySQL 任务状态、节点尝试、租约/fencing 和局部重试；未落地前不宣称 durable execution。该短期顺序优先于上面的长期候选框架顺序。

Langfuse/OpenTelemetry adapter 可继续独立演进。Mastra、Temporal 是长期候选，不作为当前预览的启动依赖；届时依据恢复、调度与运维成本再决定采用何者，不同时迁入多个 runtime，也不为未来框架创建空 port。

### 固定 DAG 第二步（持久只读预览）

在第一步上新增 MySQL task/node/attempt、版本化 workflow SPI、DB 时间租约与 epoch fencing、成功结果恢复和 revision 局部重试。后台默认关闭，复用同一个有界节点执行器。该切片不等于通用 durable Agent runtime：不支持工具幂等/业务副作用、动态图或竞速，不引入 Temporal，也不承诺 exactly-once。后续只有复杂长任务确有需求时再评估框架。详见 `docs/development/learning-plan-preview.md`。

### 固定 DAG 第三步（HTTP闭环）

先提供Learning-owned安全提交/查询/局部重试/终态删除接口；不在这一步扩大动态图、工具、副作用或引入新框架。后台默认关闭，HTTP不开新服务、不加新基础设施，仍需可用MySQL与已配置模型。前端和复杂Agent能力留作后续独立切片。


### 固定 DAG 第四步（最小前端闭环）

增加独立学习计划预览页与能力画像入口，复用第三步提交/查询/局部重试/终态删除协议。
API/composable/页面展示分工，内存幂等键、受控串行轮询、显式 revision 重试和二次确认删除；
查询失败暂停，任务切换/离开只中止客户端请求，不取消后台执行。
不引入新框架、依赖服务、动态规划、竞速、工具、安全沙箱、任务列表或自动重试。
动态图与受限执行仍需后续单独设计，不能把这一小步描述为通用 durable Agent runtime 已完成。
