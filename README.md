# code-memory

Code-aware **operational memory for coding agents** — not a static document index. It parses
a codebase into **AST-level symbols** (classes, methods, functions), keeps them up to date as
code changes, and answers an agent's "what do I need for this task?" with **hybrid retrieval
(vector + full-text + dependency graph)**, scoped to **what that agent is allowed to see**.

Java 21 / Spring Boot 3.3 on PostgreSQL + pgvector — the same hexagonal stack as the sibling
document-RAG service, re-pointed from "static docs for humans" to "live code memory for agents."

## The four transformations (vs. classic document RAG)

| Classic RAG | Here |
| --- | --- |
| Arbitrary text chunks by token count | **AST symbols** (class / method / function) with signatures |
| One-time / scheduled ingest | **Incremental re-index** per file (`/index/file`) |
| Pure vector similarity | **Hybrid: vector + full-text + dependency graph** (who-calls) |
| Global project context | **Per-agent scoping** (QA sees logic+tests, not deploy/secrets) |

## Language support

**Java**, parsed precisely via the **JDK compiler Tree API** (no external/native dependencies).
The `CodeParser` port is open, so a precise parser for another language (via tree-sitter or an
LSP) can be added later without touching the core — but the shipped app is **Java-only** and
pure Java. (Code is English — Postgres `english` full-text config.)

## Build & test
```bash
source env.sh
mvn -B verify      # unit tests + a Testcontainers pgvector IT (index → search → scope → graph)
```

## Run locally
```bash
docker compose up -d postgres          # pgvector on :5432
source env.sh && mvn spring-boot:run   # app on :8080 (runs on a JDK, needed for Java parsing)

# index a codebase, then retrieve scoped task context
curl -XPOST localhost:8080/api/index  -H 'content-type: application/json' \
  -d '{"repo":"demo","root":"/abs/path/to/code"}'
curl -XPOST localhost:8080/api/context -H 'content-type: application/json' \
  -d '{"task":"where is the retry/backoff logic?","agent":"dev","topK":8}'
```

## Endpoints
- `POST /api/index` `{repo, root}` — full walk + index.
- `POST /api/index/file` `{repo, root, path}` — incremental re-index of one file.
- `DELETE /api/index/file` `{repo, root, path}` — drop a file's symbols.
- `POST /api/search` `{query, agent, topK}` — scoped hybrid search.
- `POST /api/context` `{task, agent, topK}` — search + graph enrichment (`callers`, `tests`).
- `GET /actuator/health`.

## Configuration (`app.codemem.*` / env)
| Key | Default | Notes |
| --- | --- | --- |
| `EMBED_DIM` | `256` | hash-embedding dimension; must match the pgvector column (Flyway placeholder) |
| `app.codemem.rrf-k` | `60` | Reciprocal Rank Fusion constant |
| `app.codemem.text-search-config` | `english` | full-text config |
| `app.codemem.top-k` | `8` | default results |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | localhost/codemem | pgvector Postgres |

## Honest status
- **Phase-1 scaffold, tested** (tests incl. a real-pgvector IT) — not a finished product.
- **Embeddings are a pure-Java hash** (offline, reproducible), the *weakest* signal — structure
  (AST) + full-text do the heavy lifting. Swap in a code embedding model via the `EmbeddingProvider`
  port for quality.
- **The dependency graph is name-matched**, not compiler-grade — back it with an LSP for precision.
- **Java parsing needs a JDK at runtime** (compiler API); on a JRE it degrades to module-level symbols.
- **No authentication** — `agent` is a context boundary, not authn; front it with auth before
  multi-tenant exposure.

## License
MIT © 2026 Andrii Lisovets.
