# Local Lite Startup Design

## Goal

Add a lightweight local startup mode for development so the application can boot without eagerly connecting to optional infrastructure such as IM WebSocket gateways, retrieval warmup backends, and multimodal Milvus image collection initialization.

## Problem

The default application startup currently initializes many capabilities at once: MySQL, Redis, Milvus, Neo4j, model clients, retrieval warmup, image vector collection setup, QQ WebSocket, and Feishu WebSocket. This is appropriate for a full integrated runtime, but heavy for routine local development where a developer may only need the web API or UI.

## Scope

This phase is intentionally small and low risk:

- Add a `local-lite` Spring profile.
- Disable startup-heavy optional components in that profile.
- Add feature flags to components that currently cannot be skipped at bean creation time.
- Keep the default `application.yml` behavior compatible with the current full startup mode.

This phase does not split Maven modules, remove dependencies, replace databases, or redesign RAG internals.

## Design

### Profile Configuration

Create `src/main/resources/application-local-lite.yml`. It will:

- Disable retrieval backend warmup with `app.knowledge.retrieval.warmup-enabled=false`.
- Disable QQ and Feishu WebSocket startup with `im.qq.use-ws=false` and `im.feishu.use-ws=false`.
- Disable multimodal image Milvus collection initialization with `app.multimodal.image-store.enabled=false`.
- Reduce local Hikari pool pressure with smaller pool values.
- Reduce noisy Spring AI logging from DEBUG to INFO.

Developers can run it with:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local-lite
```

### Feature Flags

`RetrievalWarmupRunner` already has `@ConditionalOnProperty`, so it only needs profile configuration.

`ImageVectorStoreConfig` will gain:

```java
@ConditionalOnProperty(prefix = "app.multimodal.image-store", name = "enabled", havingValue = "true", matchIfMissing = true)
```

This preserves existing full startup behavior while allowing `local-lite` to skip Milvus image collection initialization.

`QqWsService` will gain:

```java
@ConditionalOnProperty(prefix = "im.qq", name = "use-ws", havingValue = "true")
```

This prevents the QQ WebSocket bean from being created when disabled. The existing internal guard remains harmless defense in depth.

`FeishuWsService` already has a conditional property and will be disabled by profile configuration.

## Testing

Add focused conditional-loading tests using `ApplicationContextRunner` where possible:

- `QqWsService` is not loaded when `im.qq.use-ws=false`.
- `ImageVectorStoreConfig` is not loaded when `app.multimodal.image-store.enabled=false`.
- Existing architecture tests continue to pass.

Final verification:

```bash
mvn -q compile
mvn -q -DskipTests test-compile
mvn -q -Dtest=ArchitectureRulesTest test
mvn -q verify -DskipTests
git diff --check
```

## Rollback

Rollback is straightforward: remove `application-local-lite.yml` and the two conditional annotations. Default full startup behavior remains unchanged throughout the change.

## Addendum: One-Command Development Startup

Add a shell-based development launcher for the lightweight local profile. The launcher starts the required Docker infrastructure, waits for the key ports, then starts both backend and frontend development servers.

### Scripts

- `scripts/dev-start.sh`
  - Starts Docker dependencies: `mysql redis etcd minio milvus neo4j`.
  - Waits for MySQL, Redis, Milvus, and Neo4j ports.
  - Starts backend with `local-lite` profile.
  - Starts frontend through `npm run dev` in `frontend/`.
  - Writes logs and PID files under `.dev/`.
  - Keeps running until interrupted, then stops backend/frontend child processes.

- `scripts/dev-stop.sh`
  - Stops backend/frontend processes recorded in `.dev/*.pid`.
  - Leaves Docker dependency containers running by default to avoid slow restarts.
  - Supports `--with-docker` to stop the dependency containers too.

### Non-goals

The script will not start RocketMQ, CLIP embedding, or optional evaluation services. It will not install frontend dependencies automatically; if `frontend/node_modules` is missing, it prints the exact `npm install` command.
