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
- **Go** — parsed **precisely** by the **chunking service** using Go's own standard library
  (`go/parser` + `go/ast`) — the language's own parser, no third-party grammars — over a
  small HTTP **chunk contract** (`POST /chunk {repo,path,language,content}` →
  `{symbols[],edges[]}`). **Python** is the next contract implementation (today it returns a
  module-level symbol). `RemoteChunkingParser` degrades gracefully (module-level symbol) if
  the service is down — indexing never fails, and the Java app stays pure (no native deps).
- Adding a language = implement/enable a `CodeParser` (or point the chunking service at a
  new grammar); the retrieval/store/scoping core is untouched. (C++ intentionally out of
  scope — precise C++ parsing needs clangd and isn't worth it yet.)
- **Contract** — the `/chunk` API is defined once in
  [`chunking-service/openapi.yaml`](chunking-service/openapi.yaml) (OpenAPI 3.1) and served
  live at `GET /openapi.yaml`. Both ends are tested against it: the Go producer (httptest)
  and the Java consumer (`ChunkContractTest` fails the build on any DTO drift).

## Deployment topologies (Kubernetes)

Helm chart: [`charts/code-memory`](charts/code-memory) (app + optional pgvector + the
chunking service). The chunking placement is one value, `chunking.topology`:

| `chunking.topology` | What runs |
| --- | --- |
| `disabled` **(default)** | No chunking workload; app in pure-`java` mode. |
| `sidecar` | Chunking container in the **same pod**; app calls `localhost`. Simple, co-scaled. |
| `service` | Chunking as its **own Deployment+Service**; independently scalable (parsing is CPU-heavy on big re-indexes). |
| `external` | No workload deployed; app calls an **already-running** chunk service at `chunking.external.baseUrl` (any producer that speaks the chunk contract). |

```bash
helm install cm charts/code-memory                                   # pure Java
helm install cm charts/code-memory --set chunking.topology=sidecar   # co-located
helm install cm charts/code-memory --set chunking.topology=service   # own Deployment
helm install cm charts/code-memory --set chunking.topology=external \
  --set chunking.external.baseUrl=http://chunker.team.svc:8090        # bring your own
```

Locally (Docker), the `service` topology is mirrored by compose profiles: plain
`docker compose up` = pure Java; `docker compose --profile chunking up` adds the service.

## Configuration (`app.codemem.*` / env)
| Key | Default | Notes |
| --- | --- | --- |
| `app.codemem.parsing.mode` | `java` | `java` \| `single` \| `multi` |
| `app.codemem.parsing.language` | _(empty)_ | required when `mode=single` |
| `app.codemem.parsing.chunking.base-url` | `http://localhost:8090` | chunking service endpoint |
| `app.codemem.sync.on-read` | `true` | self-healing git read-gate before retrieval (local: app shares the repo FS). `false` = explicit `/api/sync` + git hooks only |
| `EMBED_DIM` | `256` | hash-embedding dimension (matches the pgvector column via Flyway placeholder) |
| `app.codemem.rrf-k` / `.text-search-config` / `.top-k` | `60` / `english` / `8` | retrieval tuning |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | localhost/codemem | pgvector Postgres |

## Build & test
```bash
source env.sh
mvn -B verify      # unit tests + Testcontainers ITs: pgvector (index→search→scope→graph)
                   # and the Go chunking service built from source (real Go parse end-to-end)
```

## Run locally (Docker)

One command builds the stack as containers, indexes this repo, queries it, then tears
everything down. Nothing to download first — embeddings are a built-in pure-Java hash:

```bash
bash scripts/dev-local.sh all                     # pgvector + app (pure-Java parsing)
PARSING_MODE=multi bash scripts/dev-local.sh all  # also build/run the Go chunking service
```

Keep it up and drive the API yourself:

```bash
bash scripts/dev-local.sh up         # build + start (add PARSING_MODE=multi for Go/Python)
curl -XPOST localhost:8080/api/index   -H 'content-type: application/json' \
  -d '{"repo":"demo","root":"/workspace"}'        # /workspace = this repo, mounted read-only
curl -XPOST localhost:8080/api/context -H 'content-type: application/json' \
  -d '{"task":"where is the retry/backoff logic?","agent":"dev","topK":8}'
bash scripts/dev-local.sh down       # stop + wipe all data

MIRROR=my-mirror.example.com bash scripts/dev-local.sh all   # behind a private registry
```

Registry, image names, and host ports are all overridable — see `scripts/config.sh`. The
app runtime is a **JDK** (not JRE): the Java parser uses the JDK compiler API.

Prefer a fast host inner-loop with only the DB in Docker:

```bash
docker compose up -d postgres          # pgvector on :5432
source env.sh && mvn spring-boot:run   # app on :8080
```

## Endpoints
- `POST /api/index` `{repo, root}` — full walk + index (hash-aware: skips unchanged files).
- `POST /api/index/file` `{repo, root, path}` — incremental re-index of one file.
- `DELETE /api/index/file` `{repo, root, path}` — drop a file's symbols.
- `POST /api/sync` `{repo, root, paths?}` — reindex the repo's git delta (cursor..HEAD ∪ working tree), or exactly `paths` when given. Used by git hooks.
- `POST /api/search` `{query, agent, topK}` — scoped hybrid search.
- `POST /api/context` `{task, agent, topK}` — search + graph enrichment (`callers`, `tests`).
- `GET /actuator/health`.

## Keeping the index fresh (git sync)

**Local-oriented, self-healing.** The app runs where the repo lives — either pure-Java on
the host, or in a container with the repo **volume-mounted** (`./:/workspace`) — so it reads
the working tree and git directly (via **JGit**, no `git` binary needed). It is *not*
designed for k8s where the repo isn't mounted.

- **Read-gate (default, `sync.on-read=true`).** Before each `/api/search` / `/api/context`,
  every tracked repo is cheaply checked: `HEAD` vs the stored cursor + working-tree status.
  Only the **delta** (`cursor..HEAD` ∪ uncommitted) is re-indexed, then results are served —
  so correctness never depends on the agent remembering to sync. Steady state = one
  cheap git check. Cost is **O(changed), not O(repo)**.
- **Per-file hashes** (`code_files`) skip re-parsing unchanged files; the cursor lives in
  `repo_state`.
- **Git hooks (optional optimization).** `scripts/install-git-hooks.sh <worktree> <repo-id>
  [server-url]` installs post-commit/merge/checkout/rewrite hooks that `POST /api/sync` so a
  commit updates the index immediately; the read-gate is the backstop if a hook is missing.

Cold start: run `POST /api/index` once; after that the read-gate keeps it fresh.

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
