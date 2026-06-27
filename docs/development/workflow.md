# 开发工作流

本文档定义 Study-Agent 中人类开发者和 AI coding agent 的默认开发流程。

## 任务开始前

1. 阅读用户目标，判断影响范围：后端、前端、数据库、配置、启动脚本或文档。
2. 运行：

```bash
git status --short --branch
```

3. 阅读根目录 `AGENTS.md`。
4. 修改后端前阅读 `ARCHITECTURE.md`、`PACKAGE_CONVENTIONS.md` 和 `docs/development/backend-guidelines.md`。
5. 修改前端前阅读 `docs/development/frontend-guidelines.md`。
6. 修改启动、环境或依赖服务前阅读 `README.md` 和相关本地启动文档。

## 变更粒度

保持小步、内聚、可回滚：

- 一个 use case 一次改动。
- 一个页面或一组强相关组件一次改动。
- 一个包迁移批次一次改动。
- 不把功能开发、格式化、依赖升级和大规模重构混在一起。

如果必须跨模块，先列出 affected chain，例如：Controller -> Application -> Persistence -> Frontend API -> View -> Tests。

## TDD 默认策略

行为变更默认使用 TDD：

1. 先写或更新能暴露目标行为的最小测试。
2. 运行该测试，确认失败原因符合预期。
3. 实现最小代码。
4. 重新运行测试。
5. 再做必要重构。

纯文档、注释、机械式移动可以不新增测试，但需要说明原因。

## 后端修改流程

1. 确认代码应该属于哪个 domain。
2. 优先复用现有 application/domain/infrastructure 边界。
3. 新增 Controller 时只做协议转换，不放业务流程。
4. 新增持久化访问时放入 `<domain>.infrastructure.persistence`。
5. 新增配置项时更新配置文档或 README。
6. 运行相关单测和编译检查。

## 前端修改流程

1. 页面入口放 `frontend/src/views`。
2. HTTP 调用放 `frontend/src/api`。
3. 复杂数据装配放 `frontend/src/services`。
4. 可复用状态逻辑放 `frontend/src/composables`。
5. 新页面同步更新 `frontend/src/router` 和相关菜单入口。
6. 运行 `cd frontend && npm run build`。

## 数据库与外部依赖变更

新增表、索引、服务依赖或端口时：

- 更新 `sql/` 或迁移脚本。
- 更新配置示例和启动文档。
- 说明是否影响 MySQL、Redis、Milvus、Neo4j、MinIO、模型服务或外部 API。
- 如会影响启动耗时，说明是否可以在本地轻量模式关闭。

## 文档同步

以下情况必须同步文档：

- 命令变了。
- 目录约定变了。
- 架构边界变了。
- 新增环境变量或外部服务。
- 新增开发规范、测试要求或质量门禁。

## 完成前总结

最终回复至少包含：

- 改了哪些文件或区域。
- 行为或规范有什么变化。
- 实际运行了哪些检查，结果是什么。
- 哪些检查没运行，以及原因。
- 后续风险或建议。
