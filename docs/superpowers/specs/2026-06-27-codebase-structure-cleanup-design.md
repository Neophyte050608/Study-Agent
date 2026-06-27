# Codebase Structure Cleanup Design

## Objective

Refactor the repository toward the documented domain-first architecture while removing confirmed dead code. The main target is to eliminate the top-level horizontal persistence and DTO packages (`entity/`, `mapper/`, `dto/`) by moving each class into its owning domain package. The migration must preserve runtime behavior, database schema, table names, and public API behavior.

## Current Problems

The project already documents a domain-first structure, but implementation still contains mixed package styles:

- Persistence objects under `io.github.imzmq.interview.entity.<domain>`.
- MyBatis mappers under `io.github.imzmq.interview.mapper.<domain>`.
- DTOs under `io.github.imzmq.interview.dto.<domain>`.
- Several technical or legacy packages such as `rag.core`, `tool`, `tools.runner`, `session.repository`, and `core.trace` whose ownership is unclear.

This makes feature ownership harder to understand and encourages new code to drift away from the established `api / application / domain / infrastructure` structure.

## Target Package Model

Each business domain should own its API, application logic, domain model, and infrastructure code:

```text
io.github.imzmq.interview.<domain>/
  api/
  application/
  domain/
  infrastructure/
    persistence/
```

Persistence classes move as follows:

```text
entity/menu/MenuConfigDO   -> menu/infrastructure/persistence/MenuConfigDO
mapper/menu/MenuConfigMapper -> menu/infrastructure/persistence/MenuConfigMapper
```

DTO placement rules:

- Controller request/response DTOs move to `<domain>.api`.
- Application-internal DTOs move to `<domain>.application.dto`.
- No new top-level `dto` package should be introduced.

## Migration Strategy

Use small, domain-by-domain batches instead of a whole-repository rewrite. Each batch moves one domain's DOs, mappers, and related DTOs, updates imports, then runs verification before continuing.

Recommended order:

1. `menu`
2. `search`
3. `agent`
4. `modelrouting`
5. `chat`
6. `interview`
7. `ingestion`
8. `learning`
9. `media`
10. `knowledge`

`knowledge` is intentionally last because it has the densest cross-domain references through RAG, evaluation, feedback, media, and observability flows.

## Dead Code Policy

Only remove code that is confidently unused. A file is removable only when all conditions are true:

- It has no references from main code, test code, configuration, SQL, frontend routes, or reflective lookup points.
- It is not a Spring bean, Mapper, controller, scheduled job, or framework-discovered type.
- Compile and tests still pass after removal.

Ambiguous code should be moved or documented, not deleted.

## Legacy Package Ownership

After the main persistence migration, audit and relocate unclear packages:

- `rag.core`: move chunking, Markdown, image-reference, and note-loading utilities to `knowledge.application.indexing` or `ingestion.pipeline` based on caller ownership.
- `tool` and `tools.runner`: move MCP/tool gateways to `mcp.application` or retrieval/search-specific packages.
- `session.repository`: move persistence-backed session access to the owning chat or interview infrastructure package.
- `core.trace`: move trace context utilities to `observability.core` or `knowledge.application.observability`.

## Verification

After every domain batch, run:

```bash
mvn -q compile
mvn test
mvn -q verify -DskipTests
```

Failures should be fixed before starting the next domain. Most expected failures will be stale imports, Mapper scan assumptions, or architecture test rules.

## Non-Goals

- Do not rename database tables or columns.
- Do not change SQL schema semantics.
- Do not redesign business workflows.
- Do not replace MyBatis, Redis, Milvus, Neo4j, or other infrastructure in this cleanup.
- Do not combine unrelated feature changes with package migration.

## Expected Outcome

When complete, top-level `entity/`, `mapper/`, and `dto/` packages no longer contain business code. Persistence implementation lives inside each domain's `infrastructure.persistence` package, DTOs are closer to their API/application owners, and the repository matches `ARCHITECTURE.md` and `PACKAGE_CONVENTIONS.md` more closely.
