# InterviewReview V2 验收清单（MCP + A2A）

## A. MCP 能力接入
- [x] 存在标准 MCP Gateway（发现/调用/鉴权/审计）
- [x] 至少接入 2 个 MCP 能力（Obsidian、Code Execution）
- [x] MCP 调用支持超时、重试、熔断与降级
- [x] MCP 调用日志可按 traceId 检索

## B. A2A 协作能力
- [ ] A2A 具备请求/结果/失败事件语义
- [ ] 可重试与不可重试错误码分层明确
- [ ] 关键任务支持异步消费编排
- [ ] DLQ 支持单条/批量重放及审计统计

## C. 路由与扩展性
- [ ] TaskRouter 支持注册式能力目录
- [ ] 能力支持版本与灰度标签
- [ ] 新能力接入无需改核心 switch 逻辑
- [ ] 路由策略支持配置化

## D. Skill 加载策略
- [ ] Skill 启动阶段仅加载元信息（非全文）
- [ ] Skill 命中时才全量加载详情内容
- [ ] Skill 缓存具备 `lastModified + checksum` 失效策略
- [ ] Skill 加载指标可观测（耗时、命中率、失效次数、缺失次数）

## E. 安全与治理
- [x] `/api/task/dispatch` 已受鉴权保护
- [x] `/api/observability/**` 已受鉴权保护
- [x] 运维写操作具备 RBAC 角色控制
- [ ] A2A 消息签名验签与防重放生效
- [ ] 密钥不以明文写入仓库配置

## F. 观测与运维
- [ ] 指标覆盖吞吐、失败率、重试率、DLQ 重放率
- [ ] traceId 贯穿 API/A2A/MCP
- [ ] traceId 贯穿 Skill 加载与调用链路
- [x] 审计日志可检索并按条件筛选
- [x] 幂等状态可查看并支持安全清理

## G. 兼容与稳定性
- [x] `/api/start` `/api/answer` `/api/report` 行为兼容
- [x] `/api/task/dispatch` 兼容现有任务类型
- [x] 旧画像文件兼容读取与迁移通过
- [ ] 关键链路压测达标（需定义阈值）

## H. 测试与发布
- [x] 单测覆盖新增 MCP/A2A/安全核心逻辑
- [ ] 单测覆盖 Skill 按需全量加载与缓存失效逻辑
- [x] 集成测试覆盖异步失败与回放路径
- [x] 回归测试通过
- [x] `mvn test` 通过
- [x] 文档（spec/tasks/checklist）与实现一致
