# FileScopeMCP 开发环境接入说明

## 1. 启动方式
1. 使用 `dev` profile 启动项目：
   - `mvn spring-boot:run -Dspring-boot.run.profiles=dev`
2. 在环境变量中按需配置：
   - `APP_MCP_FILESCOPE_ENABLED=true`
   - `APP_MCP_FILESCOPE_BASE_URL=http://localhost:38200`

## 2. 配置位置
1. 配置文件：`src/main/resources/application-dev.yml`
2. 配置前缀：`app.mcp.filescope.*`
3. 当前提供配置项：
   - `enabled`
   - `base-url`
   - `capability-dependency-graph`
   - `capability-symbol-search`

## 3. 最小验证步骤
1. 确认项目以 `dev` profile 启动且 `APP_MCP_FILESCOPE_ENABLED=true`。
2. 访问 MCP 能力发现接口，确认出现 `filescope` 相关能力名。
3. 发起一次依赖图查询（`filescope.dependency.graph`），验证返回模块依赖关系。

## 4. 依赖拓扑验证记录（最小样例）
基于当前代码结构，验证目标是输出以下关键关系：
1. `controller -> service -> tool`
2. `service -> rag`
3. `service -> agent`
4. `agent -> a2a`

示例目标链路：
1. `InterviewController -> InterviewService -> McpGatewayService`
2. `McpGatewayService -> FastMcpCapabilityGateway / DatabaseMcpAdapterRouter`
3. `DatabaseMcpAdapterRouter -> Neo4jMcpAdapter / MilvusMcpAdapter`

## 5. 生产隔离说明
1. FileScopeMCP 配置仅放在 `application-dev.yml`。
2. 默认 `enabled=false`，不会影响非 dev 环境启动。
