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

## Parsing: modes, languages, and the chunking service

Parsing is pluggable behind the `CodeParser` port. A `ParserRouter` dispatches each file to
a parser by **auto-detected language** (`LanguageDetector`, extension-based). How many
languages are enabled is a config choice (`app.codemem.parsing.mode`):

| Mode | Behaviour |
| --- | --- |
| `java` **(default)** | Only Java, parsed **in-process** by the JDK compiler API. No external service. |
| `single` (`language: java\|python\|go`) | One language. Java in-process; Python/Go via the chunking service. |
| `multi` | Auto-detect and dispatch: Java in-process, Python/Go to the chunking service. |

- **Java** — precise AST (JDK compiler Tree API), pure Java, in-process.
- **Python / Go** — parsed by the external **chunking service** over a small HTTP
  **chunk contract** (`POST /chunk {repo,path,language,content}` → `{symbols[],edges[]}`).
  The service uses tree-sitter/LSP in its own ecosystem, so the Java app stays pure and
  gains languages without native dependencies. `RemoteChunkingParser` degrades gracefully
  (module-level symbol) if the service is down — indexing never fails.
- Adding a language = implement/enable a `CodeParser` (or point the chunking service at a
  new grammar); the retrieval/store/scoping core is untouched. (C++ intentionally out of
  scope — precise C++ parsing needs clangd and isn't worth it yet.)

## Deployment topologies (Kubernetes)

The chunking service is optional and its placement is a Helm choice:

| `chunking.topology` | What runs |
| --- | --- |
| `disabled` **(default)** | No chunking container; app in pure-`java` mode. |
| `sidecar` | Chunking container in the **same pod**; app calls `localhost`. Simple, co-scaled. |
| `service` | Chunking as its **own Deployment+Service** in the namespace; independently scalable (parsing is CPU-heavy on big re-indexes). |

Locally (Docker), the `service` topology is mirrored by compose profiles: plain
`docker compose up` = pure Java; `docker compose --profile chunking up` adds the service.

## Configuration (`app.codemem.*` / env)
| Key | Default | Notes |
| --- | --- | --- |
| `app.codemem.parsing.mode` | `java` | `java` \| `single` \| `multi` |
| `app.codemem.parsing.language` | _(empty)_ | required when `mode=single` |
| `app.codemem.parsing.chunking.base-url` | `http://localhost:8090` | chunking service endpoint |
| `EMBED_DIM` | `256` | hash-embedding dimension (matches the pgvector column via Flyway placeholder) |
| `app.codemem.rrf-k` / `.text-search-config` / `.top-k` | `60` / `english` / `8` | retrieval tuning |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | localhost/codemem | pgvector Postgres |

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
