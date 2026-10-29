# Chat 长期记忆生命周期治理设计

**日期：** 2026-10-29

**状态：** 已确认，待实施计划
**范围：** Chat 结构化长期记忆第二阶段

## 1. 背景

结构化长期记忆第一阶段已经建立以下链路：

```text
用户消息
  -> 持久化提取任务
  -> 严格候选提取和来源引文校验
  -> MySQL 记忆项与来源
  -> 事务 Outbox
  -> Milvus 派生投影
  -> 查询召回
  -> MySQL 真相重校验
  -> Chat 上下文注入
```

MySQL 是唯一真相源，Milvus 只是可重建的向量投影。现有模型用 `(user_id, category, content_hash)` 识别完全相同的内容，只能处理精确去重，不能表达以下语义：

- 同一主题的新偏好、目标或约束替代旧值；
- 冲突候选进入隔离区后接受审计和处理；
- 一条记忆从何时开始有效、何时退出召回；
- 旧记忆为什么被替代，以及由哪条记忆替代；
- 后台任务延迟完成时，旧消息不能覆盖较新的当前事实。

本阶段完善数据模型和生命周期治理，不接入 Agent Context 的 `RECALL` slot，也不扩展为通用图记忆平台。

## 2. 目标

本阶段交付以下能力：

1. 为每条新记忆建立受约束的主题键和后端确定的主题基数。
2. 由后端确定性处理精确重复、自动替代、多值共存和不确定冲突。
3. 保存可审计的替代及冲突关系，同时保持简单的当前状态读模型。
4. 让隔离项具备查询、确认替代、确认共存和删除的后端闭环。
5. 保证召回和 Milvus 投影只包含当前可召回的叶子记忆。
6. 通过事务、数据库约束、CAS 和真实 MySQL 测试保证并发正确性。

## 3. 设计原则

- **MySQL 是唯一真相源。** Milvus 不决定主题、冲突、替代或有效期。
- **模型没有生命周期修改权。** 模型只输出候选内容、主题键和可验证来源。
- **确定性优先。** 只有规范主题键完全相同且规则明确时，后端才自动替代。
- **保留来源和演化证据。** 自动替代不会删除旧节点或旧来源。
- **删除与替代分离。** 用户删除使用 `DELETED`；系统替代使用 `SUPERSEDED`。
- **单一运行模型。** 迁移后所有数据走同一套读取和治理逻辑，不保留 legacy 分支。
- **不创建空架构。** 演化表必须直接服务管理、审计和测试，不引入空 port、空 adapter 或顶层 `memory` 模块。

## 4. 已选方案

采用“当前状态字段 + 追加式演化关系”的模型。

`t_chat_memory_item` 保存召回和治理所需的当前状态；新增 `t_chat_memory_evolution` 保存不可变的替代和冲突关系。系统不采用完整事件溯源，也不把 Neo4j 设为记忆真相源。

该方案兼顾以下需求：

- 当前召回无需重放事件；
- 一条隔离项可关联多条冲突记忆；
- 自动替代和用户删除语义清楚；
- 管理操作可以解释一条记忆为什么退出召回；
- 未来出现真实图查询需求时，可以从 MySQL 构建 Neo4j 派生投影。

## 5. 领域模型

### 5.1 类别与主题基数

保留现有类别：

```text
PREFERENCE
GOAL
FACT
CONSTRAINT
```

后端决定主题基数，模型不得输出基数：

| 类别 | 默认基数 | 行为 |
|---|---|---|
| `PREFERENCE` | `SINGLE` | 同主题的新值替代旧值 |
| `GOAL` | `SINGLE` | 同主题的新值替代旧值 |
| `CONSTRAINT` | `SINGLE` | 同主题的新值替代旧值 |
| `FACT` | `MULTI` | 同主题的不同事实默认共存 |

只有代码中明确登记的 `FACT` 主题可以是 `SINGLE`。登记表必须保持有限，只纳入确实具有单值语义的主题。无法证明单值时使用 `MULTI`，不得用向量相似度猜测基数。

### 5.2 主题键

候选增加必填 `subjectKey`。规范格式为：

```text
<category-lowercase>.<segment>[.<segment>...]
```

示例：

```text
preference.response_language
goal.target_role
constraint.available_study_time
fact.current_city
```

后端执行以下校验和归一化：

- Unicode NFKC 归一化；
- 转为小写；
- 限制总长度和段数；
- 每段只允许字母、数字和下划线；
- 第一段必须与 `category` 一致；
- 通过有限的后端别名表归一到规范键。

只有规范键完全相同，系统才可以自动判断为同一主题。Milvus 相似度和文本语义相似度不参与真相源修改。

### 5.3 候选契约

严格候选 JSON 包含：

```text
category
subjectKey
content
sourceMessageId
quote
```

模型不得输出或控制以下字段：

```text
status
cardinality
relation
supersededMemoryId
validFrom
validUntil
```

未知字段、非法主题键、无效引文、敏感内容和不符合长期记忆边界的内容继续按现有严格规则拒绝。默认提取 Prompt 使用通用主题键规则，不包含 Saber 专用词表。

自定义 Prompt 不被迁移静默覆盖；如果其输出不满足新版严格契约，提取任务以稳定错误码失败，不采用宽松兼容解析。

### 5.4 状态

记忆项状态扩展为：

```text
ACTIVE
QUARANTINED
SUPERSEDED
DELETED
```

状态语义如下：

- `ACTIVE`：当前可参与召回的记忆；
- `QUARANTINED`：来源可信，但生命周期决策不明确，不能召回或投影；
- `SUPERSEDED`：已被较新的确定性记忆替代，保留来源和演化证据；
- `DELETED`：用户、管理操作或来源失效明确删除。

现有 `version` 继续只承担 CAS 和 Outbox 投影版本职责，不表示语义修订号。

### 5.5 系统时间线

第一期只记录系统可验证的有效期，不解析“去年”“曾经”“以后”等自然语言时间：

- `valid_from` 取来源消息的创建时间；
- 新记忆替代旧记忆时，旧记忆的 `valid_until` 取新记忆的 `valid_from`；
- 用户删除当前记忆时，`valid_until` 和 `deleted_at` 取删除发生时间；
- `QUARANTINED` 记录来源时间，但在激活前不属于有效召回区间。

消息的新旧按来源顺序判断，不按提取任务完成时间判断。稳定顺序键由以下字段组成：

```text
source message created_at
source message primary key
quote start offset
```

同一提取批次中，同一单值主题出现多个不同候选时，不用引文顺序猜测用户意图，全部进入隔离区。

## 6. 数据库设计

新增不可变迁移：

```text
V6__evolve_chat_memory_lifecycle.sql
```

不得修改 V3 至 V5。

### 6.1 `t_chat_memory_item`

新增字段：

```text
subject_key
subject_cardinality        SINGLE / MULTI
valid_from
valid_until
origin_message_created_at
origin_message_pk
origin_quote_start
quarantine_reason
```

`origin_*` 字段只保存稳定排序所需元数据，不复制消息正文或引文。

现有 `deleted_at` 继续表示明确删除时间。`SUPERSEDED` 只设置 `valid_until`，不设置 `deleted_at`。

### 6.2 数据库唯一性

现有唯一约束 `(user_id, category, content_hash)` 必须移除。用户以后可能重新采用曾经使用过的偏好，此时应形成新的演化节点，而不是复活旧节点。

MySQL 使用生成列建立活动行约束：

```text
active_singleton_marker =
  CASE WHEN status = 'ACTIVE' AND subject_cardinality = 'SINGLE'
       THEN 1 ELSE NULL END

active_content_marker =
  CASE WHEN status = 'ACTIVE'
       THEN 1 ELSE NULL END
```

建立唯一索引：

```text
UNIQUE(user_id, category, subject_key, active_singleton_marker)
UNIQUE(user_id, category, subject_key, content_hash, active_content_marker)
```

MySQL 唯一索引允许存在多条 `NULL`，因此数据库保证：

- 同一用户、类别和主题最多存在一个活动单值记忆；
- 同一用户、类别、主题和内容最多存在一个活动记忆；
- 历史、隔离和删除节点可以保留重复内容。

Java 规则负责给出业务错误和事务编排，数据库约束负责并发兜底。

### 6.3 `t_chat_memory_evolution`

新增表：

```text
id
user_id
from_memory_id
to_memory_id
relation_type
created_at
```

第一期仅允许：

```text
SUPERSEDES
CONFLICTS_WITH
```

方向统一定义为：

- `SUPERSEDES`：`from_memory_id` 是新记忆，`to_memory_id` 是被替代的旧记忆；
- `CONFLICTS_WITH`：`from_memory_id` 是隔离记忆，`to_memory_id` 是相关的当前记忆。

关系采用追加式记录，不修改、不删除。隔离项是否仍待处理由其当前状态判断，不额外维护可变的 `resolved_at`，避免关系表成为第二套状态源。

表需要防止完全重复关系，并建立按用户、起点和终点查询的索引。关系写入前必须验证两端属于同一用户。

### 6.4 旧数据迁移

迁移不清空、不静默删除，也不重新抽取旧消息。旧行按以下规则回填：

```text
subject_key = <category-lowercase>.legacy_<memory_id>
valid_from = created_at
valid_until = NULL
origin_message_created_at = valid_from
origin_message_pk = 最早来源主键；无来源时使用稳定哨兵值
origin_quote_start = 最早来源偏移；无来源时使用稳定哨兵值
subject_cardinality = 对应类别的默认基数
```

每条旧记忆使用独立的 `<category>.legacy_<id>` 主题，因此不会在迁移中被错误合并或替代。迁移后，所有代码只走新模型；`<category>.legacy_<id>` 是历史数据的稳定主题身份，不是兼容模式。

## 7. 确定性写入规则

### 7.1 决策表

| 条件 | 处理 |
|---|---|
| 主题键非法或前缀与类别不一致 | 拒绝候选，不写记忆 |
| 同主题、同内容且已有活动记忆 | 不新建记忆，只追加或恢复经过验证的来源 |
| `PREFERENCE / GOAL / CONSTRAINT` 同主题出现较新的不同内容 | 原子创建新 `ACTIVE`，将旧项改为 `SUPERSEDED`，写 `SUPERSEDES` |
| `FACT/MULTI` 同主题出现不同内容 | 创建并存的 `ACTIVE` 记忆 |
| 后端登记的 `FACT/SINGLE` 同主题出现较新的不同内容 | 按单值规则替代 |
| 同批次同一单值主题出现多个不同候选 | 全部进入 `QUARANTINED` |
| 单值主题异常存在多个活动项 | 新候选进入 `QUARANTINED` |
| 旧消息的提取任务晚于较新当前记忆完成 | 候选进入 `QUARANTINED`，原因是 `OLDER_THAN_CURRENT` |

### 7.2 隔离原因

第一期使用有限的机器可读原因码：

```text
AMBIGUOUS_BATCH_ORDER
MULTIPLE_ACTIVE_SINGLETONS
SUBJECT_CARDINALITY_CONFLICT
OLDER_THAN_CURRENT
```

`QUARANTINED` 只保存“来源可信但生命周期不明确”的候选。非法格式、引文不匹配、凭证、提示注入或越过长期记忆边界的候选直接拒绝，不能伪装成待审核数据。

隔离项保存经过验证的来源，并对所有相关当前项写 `CONFLICTS_WITH` 关系。它不参与召回，也不产生 Milvus UPSERT。

### 7.3 原子替代

自动替代在单个 MySQL 事务中完成：

```text
锁定用户和主题的当前状态
  -> 再次验证来源顺序和活动单值约束
  -> 旧项改为 SUPERSEDED 并设置 valid_until
  -> 新项写为 ACTIVE
  -> 写 SUPERSEDES 关系
  -> 为旧项写 DELETE Outbox
  -> 为新项写 UPSERT Outbox
```

任何步骤失败，状态、关系和 Outbox 全部回滚。

为避免死锁，批量候选和同主题行必须按稳定键排序后加锁。数据库唯一索引和 CAS 负责拦截竞态；冲突不能静默覆盖。

## 8. 隔离治理闭环

扩展现有 `ChatMemoryOperations`，不新增平行 facade。公开契约提供：

```text
按状态分页查询记忆
查询一条记忆的演化关系
确认隔离项替代当前项
确认隔离项与当前项共存
按 expectedVersion 删除记忆
```

### 8.1 确认替代

操作必须：

1. 校验当前用户、隔离项状态和 `expectedVersion`；
2. 锁定隔离项及其同主题当前项；
3. 验证 `CONFLICTS_WITH` 目标仍然是同主题的当前项；
4. 验证隔离项来源顺序晚于所有待替代当前项；
5. 将当前项改为 `SUPERSEDED`，将隔离项改为 `ACTIVE`；
6. 追加 `SUPERSEDES` 关系；
7. 写当前项 DELETE 和新活动项 UPSERT Outbox。

如果冲突对象或主题状态已经变化，返回稳定的 stale/conflict 错误，不基于旧查询结果强行覆盖。

`OLDER_THAN_CURRENT` 的单值隔离项不能通过确认替代反向覆盖较新事实。用户若要恢复旧值，应在新消息中重新陈述，使系统获得新的来源时间。多值主题仍可按共存规则处理。

历史 `CONFLICTS_WITH` 关系保留；隔离项变为 `ACTIVE` 后，它们只承担审计用途，不再表示待处理状态。

### 8.2 确认共存

确认共存只允许 `subject_cardinality = MULTI`：

```text
锁定隔离项和同主题当前状态
  -> 校验 expectedVersion、来源和基数
  -> 隔离项改为 ACTIVE
  -> 写 UPSERT Outbox
```

单值主题不能通过该操作绕过唯一性规则。

### 8.3 删除

删除隔离项：

- 改为 `DELETED`；
- 设置 `deleted_at`；
- 保留历史冲突关系；
- 因其从未投影，不生成无意义的 Milvus DELETE。

删除活动项：

- 改为 `DELETED`；
- 设置 `valid_until` 和 `deleted_at`；
- 增加版本并写 DELETE Outbox。

删除历史项仍保留演化关系，以免破坏审计链；公开查询根据权限和用途决定是否返回已删除节点的最小元数据。

## 9. 公开 API

Controller 继续只调用 application contract，并通过现有身份契约解析当前用户。建议接口：

```text
GET    /api/chat/memories?status=QUARANTINED
GET    /api/chat/memories/{id}/evolution
POST   /api/chat/memories/{id}/resolve/replacement
POST   /api/chat/memories/{id}/resolve/coexistence
DELETE /api/chat/memories/{id}?expectedVersion=...
```

公开 DTO 增加治理必需字段：

```text
subjectKey
subjectCardinality
validFrom
validUntil
quarantineReason
version
relations
```

契约不得暴露 MyBatis DO、Mapper、Milvus SDK 或内部异常。管理写操作返回稳定错误码，并以 HTTP 冲突响应表达版本过期或状态变化。

本阶段提供后端闭环，不开发新前端页面。

## 10. 召回规则

记忆必须同时满足以下条件才能进入召回：

```text
status = ACTIVE
valid_from <= now
valid_until IS NULL OR valid_until > now
至少存在一个未失效且当前用户可访问的来源
```

以下状态永不参与召回：

```text
QUARANTINED
SUPERSEDED
DELETED
```

Milvus 候选继续接受 MySQL 重校验；词法降级查询使用同一套有效性条件。即使投影删除延迟，旧记忆也不能重新进入上下文。

演化关系只服务治理和审计，不拼接到模型上下文，避免把冲突过程误当作用户事实。

来源失效沿用现有传播路径：活动或隔离项失去最后一个有效来源后转为 `DELETED`；活动项写 DELETE Outbox。已经 `SUPERSEDED` 的历史项保持非召回状态和演化证据。

## 11. Milvus 投影与 Outbox

Milvus 只保存当前可召回的活动叶子：

| 状态变化 | 投影操作 |
|---|---|
| 新建 `ACTIVE` | UPSERT |
| `ACTIVE -> SUPERSEDED` | DELETE |
| `ACTIVE -> DELETED` | DELETE |
| 新建 `QUARANTINED` | 无 |
| `QUARANTINED -> ACTIVE` | UPSERT |
| `QUARANTINED -> DELETED` | 无 |

替代事务同时产生旧项 DELETE 和新项 UPSERT。正确性不依赖这两个事件的消费顺序：

- MySQL 始终是真相源；
- 召回始终重校验状态和有效期；
- Outbox 最终让 Milvus 收敛；
- 每条记忆继续使用 `version` 拒绝过期投影事件。

演化关系不投影到 Milvus，本阶段也不投影到 Neo4j。修复任务只根据 MySQL 当前状态补齐或清理投影，不重新推断演化关系。

## 12. 故障、恢复与降级

- 生命周期事务失败时，记忆状态、演化关系和 Outbox 一起回滚。
- Milvus 不可用时，MySQL 写入和治理继续工作，Outbox 保留重试。
- 管理操作遇到版本或状态冲突时返回稳定错误，不自动覆盖。
- 后台旧任务晚完成时进入隔离区，不反向替代新事实。
- 服务重启只恢复现有持久化任务和 Outbox，不扫描历史消息补造任务。
- `local-lite` 不新增必需基础设施；Milvus 不可用时继续使用符合生命周期条件的 MySQL 降级召回。

## 13. 可观测性

在现有内容脱敏的记忆观测链上增加：

- 自动替代数量；
- 隔离数量及有限原因码；
- 确认替代、确认共存和删除数量；
- 生命周期版本或状态冲突数量；
- 投影删除、重试和修复数量。

日志、指标和 trace 不记录：

- 记忆正文或引文；
- `subjectKey`；
- 用户、会话、消息或记忆标识；
- prompt、raw JSON、embedding 或凭证。

新增事件和字段必须同步 `docs/development/observability-guidelines.md`。

## 14. 测试与验收

### 14.1 领域和解析

- `subjectKey` 的 NFKC、小写、类别前缀、长度、段数和非法字符校验；
- 有限别名归一化；
- 类别默认基数和登记的单值事实主题；
- 严格候选 JSON 与未知字段拒绝；
- 模型不能指定状态、基数、有效期或演化关系。

### 14.2 事务和并发

- 同主题同内容只追加或恢复来源；
- 较新的单值候选原子替代旧值；
- `FACT/MULTI` 正常共存；
- 同批歧义全部进入隔离；
- 旧任务晚完成进入 `OLDER_THAN_CURRENT`，不反向覆盖；
- 并发提交由锁、唯一约束和 CAS 保证最多一个活动单值；
- 状态、关系和两个 Outbox 事件同事务提交或回滚；
- 管理操作严格校验用户、状态、来源顺序和 `expectedVersion`；
- 单值主题不能确认共存，旧来源不能确认替代新事实。

### 14.3 召回和投影

- 只有当前有效的 `ACTIVE` 记忆被召回；
- `QUARANTINED`、`SUPERSEDED` 和 `DELETED` 永不进入上下文；
- Milvus 过期命中被 MySQL 拦截；
- Milvus 故障时 MySQL 降级仍遵守生命周期；
- 投影事件乱序不能复活旧记忆；
- 最后一个来源失效后，活动记忆退出召回并清理投影。

### 14.4 迁移与架构

至少运行：

```bash
mvn -q -Dtest=FlywayDatabaseMigrationTest test
mvn -q -Dtest=FlywayMySqlMigrationIntegrationTest test
mvn -q -Dtest=ArchitectureRulesTest test
```

随后运行记忆相关测试和全量：

```bash
mvn test
```

数据库生成列、唯一性、锁顺序、CAS 和事务回滚必须由真实 MySQL 集成测试证明，不能只依赖 mock 或 H2。

## 15. 文档影响

实施时同步更新：

- `PACKAGE_CONVENTIONS.md`：Chat memory ownership 和公开契约；
- `ARCHITECTURE.md`：长期记忆当前状态与演化关系；
- `docs/development/observability-guidelines.md`：生命周期事件和脱敏字段；
- 相关启动、配置或 API 文档：仅在实际行为发生变化时更新。

## 16. 非目标

本阶段明确不做：

- 不接入 Agent Context `RECALL` slot；
- 不解析自然语言有效期；
- 不引入 Neo4j 演化投影；
- 不使用相似度自动替代；
- 不开发记忆管理前端；
- 不回放历史消息或重新抽取旧数据；
- 不引入通用事件溯源框架；
- 不增加 Maven 模块、顶层 `memory` 包或空 port/adapter；
- 不增加 API Key 或新的启动依赖。

## 17. 实施边界

该设计可以拆成数据库与领域模型、确定性写入、治理 API、召回与投影收敛、集成验证五组小步提交，但这些提交共同实现一套最终模型。实施期间不得引入双写、双读、临时兼容构造器、旧版宽松候选解析或无消费者的预留抽象。
