# Code-Memory — Test Specification

Senior-tester view. Treats the system as a specification, not an implementation. Covers
black-box and white-box levels from unit up to real-world E2E, plus non-functional and
edge coverage. Each case carries an ID, box type, and technique.

---

## 1. System Under Test (SUT) — black-box model

A service that ingests a codebase and answers questions about it, grounded in the actual
code, scoped per agent role.

**Capabilities**
- **Ingest/Index** code (full repo or single file), parsing it into **symbols**
  (functions/classes/methods) with signatures, locations, and dependency edges.
- **Retrieve** by meaning + exact text (hybrid), returning ranked symbols with citations
  (file + lines).
- **Context** assembly: results enriched with graph relations (who-calls, which-tests).
- **Scope** results to the caller's **agent role** (access boundary).
- **Incremental** updates: re-index a changed file; delete a removed file.
- **Multi-language** via auto-detect; Java in-process, others via an external chunking service.

**Interfaces (black-box surface)**
- `POST /index {repo, root}` → `{files, symbols, edgesResolved}`
- `POST /index/file {repo, root, path}` → `{symbols}`
- `DELETE /index/file {repo, root, path}` → `{deleted}`
- `POST /search {query, agent, topK}` → `{results[]}`
- `POST /context {task, agent, topK}` → `{results[], callers{}, tests{}}`
- `GET /health`
- Config: parsing mode (`java|single|multi`), language, chunking endpoint, embed dim,
  RRF k, top-k, DB/scope rules.

---

## 2. Objectives, scope, techniques

**Objectives:** correctness of parsing, retrieval relevance & ranking, **RBAC isolation
(security-critical)**, freshness after edits, graph accuracy, API contract stability,
resilience to a failing parser service, and performance at scale.

**In scope:** all levels below. **Out of scope:** the LLM/agent that *consumes* context
(we test retrieval, not generation), and third-party grammar correctness (trusted).

**Techniques mapped to levels**

| Level | Box | Primary techniques |
|---|---|---|
| Unit | White | Statement/branch/condition coverage, boundary value analysis (BVA), control/data-flow |
| Component | Black+White | Equivalence partitioning (EP), decision tables, state transition |
| API/contract | Black | Schema validation, status-code matrix, EP/BVA, error guessing |
| Integration | Black | Pipeline/data-flow, pairwise (mode × language) |
| System/E2E | Black | Real-world scenario, use-case, exploratory |
| Non-functional | Black | Load/stress/soak, fault injection, security pen-style |
| Edge/negative | Black+White | Error guessing, boundary, fuzzing |

---

## 3. Unit tests (white-box)

**Parser / symbol extraction (`UT-PARSER`)**
- UT-PARSER-01 Extracts class, method, nested method with correct `symbolPath` (`Outer.Inner.m`).
- UT-PARSER-02 Correct start/end line and signature (BVA: 1-line method, file-final method, no trailing newline).
- UT-PARSER-03 Call expression → `calls` edge with callee name (member-select vs bare call).
- UT-PARSER-04 Import statement → `imports` edge.
- UT-PARSER-05 Always emits a `<module>` symbol.
- UT-PARSER-06 Malformed/partial source → no crash, degrades to module symbol.
- UT-PARSER-07 Empty / comments-only / unicode identifiers / very long line.
- UT-PARSER-08 Anonymous/lambda constructs don't produce bogus symbols.

**Language detector (`UT-DETECT`)**
- UT-DETECT-01 `.java/.py/.go` → correct language; case-insensitive extension.
- UT-DETECT-02 Unknown/extensionless → empty.
- UT-DETECT-03 Multi-dot path resolves on last extension.

**Parser router (`UT-ROUTER`)** — decision table over `mode × fileLanguage`
- UT-ROUTER-01 `mode=java`: `.java` supported; `.py/.go` not.
- UT-ROUTER-02 `mode=single(python)`: `.py` → remote; `.java` not supported.
- UT-ROUTER-03 `mode=multi`: java→in-process, py/go→remote.
- UT-ROUTER-04 `mode=single` + unsupported language → fail-fast config error.
- UT-ROUTER-05 Unknown extension → empty result, no dispatch.

**Embedding (`UT-EMBED`)**
- UT-EMBED-01 Deterministic (same input → identical vector).
- UT-EMBED-02 Correct dimension == configured dim.
- UT-EMBED-03 L2-normalised for non-empty; empty → no NaN.
- UT-EMBED-04 Stable across processes.

**RRF / ranking (`UT-RANK`)**
- UT-RANK-01 Score = Σ 1/(k+rank); both-list item outranks single-list item.
- UT-RANK-02 `k` boundary (1 vs 60) reweights as specified.
- UT-RANK-03 Deterministic order on tie scores.

**Scope resolver (`UT-SCOPE`)** — decision table path → roles
- UT-SCOPE-01 Default → `all`.
- UT-SCOPE-02 `test/`, `*_test.*`, `*.test.*` → `dev,qa`.
- UT-SCOPE-03 `deploy/`, `charts/`, `helm/`, `*.env`, `*secret*` → `devops`.
- UT-SCOPE-04 Precedence with multiple matches (`deploy/secret.env`).
- UT-SCOPE-05 Windows separators, mixed case, leading `./`.

**Serialization (`UT-SER`)**
- UT-SER-01 Vector → pgvector literal round-trip + precision.
- UT-SER-02 Roles → `text[]` literal escaping (quote, comma, backslash, injection chars).

---

## 4. Component tests (adapters in isolation)

**Store (pgvector) — Testcontainers (`CT-STORE`)**
- CT-STORE-01 Migrations apply on a fresh DB; re-run idempotent.
- CT-STORE-02 `upsertFile` inserts symbols + edges; counts correct.
- CT-STORE-03 Re-`upsertFile` **replaces** prior symbols (no duplicates, no stale rows).
- CT-STORE-04 `deleteFile` removes only that file's symbols + edges.
- CT-STORE-05 `search` returns vector-only, text-only, and fused hits; ordered by score.
- CT-STORE-06 Exact-token search (identifier / `ERR-503-GATEWAY`) succeeds where vector alone fails.
- CT-STORE-07 Scope filter: `[engineering]` excluded for `{all}`; included for `{engineering}`.
- CT-STORE-08 `callersOf` / `testsFor` resolve by `dst_id` and trailing-name fallback.
- CT-STORE-09 `resolvePendingEdges` links names→ids; ambiguity handled.
- CT-STORE-10 Dimension mismatch → clear failure, not silent corruption.

**Remote chunking parser — stubbed HTTP (`CT-REMOTE`)**
- CT-REMOTE-01 Maps contract JSON → symbols + edges with correct ids.
- CT-REMOTE-02 Sends `{repo,path,language,content}`; language auto-filled.
- CT-REMOTE-03 5xx / timeout / connection-refused → degrades to module symbol, no exception.
- CT-REMOTE-04 Malformed JSON / missing fields / null arrays → safe.
- CT-REMOTE-05 Respects connect/read timeouts (no hang).

---

## 5. API / contract tests (black-box)

- API-IDX-01 `POST /index` valid → 200 `{files,symbols,edgesResolved}`.
- API-IDX-02 Missing/blank `repo`/`root` → 400.
- API-IDX-03 Non-existent `root` → defined 4xx, not 500.
- API-IDXF-01 `POST /index/file` valid → 200 `{symbols}`.
- API-DEL-01 `DELETE /index/file` → 200 `{deleted}`; absent file → `{deleted:0}` (idempotent).
- API-SEARCH-01 Valid → 200, `results[]` items have `id,file,symbol,kind,lines,allowedAgents,score`.
- API-SEARCH-02 Missing/blank `query` → 400.
- API-SEARCH-03 `topK` default when omitted; `0`/negative → default or 400; huge → bounded.
- API-SEARCH-04 `agent` omitted → least-privileged visibility, never elevated.
- API-CTX-01 `POST /context` → 200 with `results`, `callers{}`, `tests{}` keyed by symbol id.
- API-CTYPE-01 Non-JSON content-type → 415.
- API-HEALTH-01 `GET /health` → 200 `{status:UP}`.
- API-ERR-01 Unhandled error → 500 with non-leaky body (no stack/SQL/paths).

---

## 6. Integration tests (pipeline, black-box) — Testcontainers

- IT-PIPE-01 Index small repo → search returns a known symbol with correct file/lines.
- IT-PIPE-02 `context` returns callers for a called symbol.
- IT-PIPE-03 Incremental: index → modify method → `index/file` → new content returned, old gone.
- IT-PIPE-04 Delete: `index/file` DELETE → symbol & edges gone.
- IT-PIPE-05 Mode pairwise: java in-process and multi+stub-service both index & retrieve.
- IT-PIPE-06 Re-index twice → stable counts, no duplicates.

---

## 7. System / E2E — real code, real questions (black-box)

**Approach:** download real, version-pinned repositories, index them, pose natural
questions, assert retrieved symbols/citations are correct. Corpora: a small Java service, a
Python library, a Go CLI.

| ID | Upload | Question / action | Expected |
|---|---|---|---|
| E2E-01 | Java service repo | `POST /index` | files/symbols > 0; within SLA |
| E2E-02 | — | "where are HTTP calls retried?" | top hit = retry/backoff method; citation correct |
| E2E-03 | — | exact token (method name / error code) | exact match ranks #1 |
| E2E-04 | — | context "fix the rate limiter" (agent=dev) | limiter class + callers + covering test |
| E2E-05 | repo w/ `deploy/`+secrets | search as `agent=qa` | no deploy/secret symbols |
| E2E-06 | same | search as `agent=devops` | deploy symbols visible |
| E2E-07 | — | ask about nonexistent symbol | empty/low-confidence, no fabricated citation |
| E2E-08 | — | edit method → re-index file → re-ask | answer updated in one request (freshness) |
| E2E-09 | — | delete file → re-ask | its symbols gone |
| E2E-10 | Python + Go repos (multi) | index + search | language auto-detected; retrievable per language |
| E2E-11 | Monorepo (Java+Py+Go) | search a cross-cutting concept | results span languages, scoped & cited |
| E2E-12 | large/generated file | index | no OOM/timeout; reasonable symbols |
| E2E-13 | re-upload updated repo (many changes) | re-index | stale symbols purged; counts match new state |

**Relevance acceptance:** maintain a graded **gold Q&A set** (expected symbol ids). Report
**precision@k / recall@k / MRR**; gate on agreed thresholds (e.g. top-1 ≥ 0.7, recall@5 ≥ 0.85).

---

## 8. Non-functional tests

**Performance / scalability (`NFR-PERF`)**
- NFR-PERF-01 Query p95 < target (e.g. 300 ms) at 10k / 100k / 1M symbols.
- NFR-PERF-02 Ingest throughput; full-index time for 100k-LOC repo within SLA.
- NFR-PERF-03 Incremental `index/file` latency < interactive target.
- NFR-PERF-04 HNSW recall-vs-latency as vector count grows.
- NFR-PERF-05 Soak 1h: no memory leak / pool exhaustion.
- NFR-PERF-06 Concurrency: K parallel index+query clients hold correctness + latency.

**Security / RBAC (critical) (`NFR-SEC`)**
- NFR-SEC-01 No cross-role leak: exhaustive {aclTags} × {roles} visibility matrix.
- NFR-SEC-02 No elevation via extra/unknown roles; unknown role sees only `public/all`.
- NFR-SEC-03 Scope enforced in both modes, before ranking (restricted rows never influence results).
- NFR-SEC-04 Injection (query/repo/path/role with SQL metachars, quotes, backslash, unicode) → parameterised, no breakout.
- NFR-SEC-05 Errors leak no file content / SQL / internal paths.
- NFR-SEC-06 (When auth added) unauthenticated rejected; roles from verified identity, not raw header.

**Resilience / fault injection (`NFR-RES`)**
- NFR-RES-01 Chunking service down/slow → non-Java indexing degrades; Java unaffected; query path independent of it.
- NFR-RES-02 DB drop mid-op → clean error, recovery, no partial-corrupt index.
- NFR-RES-03 Interrupted full index → re-run converges (idempotent upsert).
- NFR-RES-04 Oversized / binary / non-UTF-8 file → skipped with log, pipeline continues.

**Data lifecycle (`NFR-DATA`)**
- NFR-DATA-01 Deleting repo/file leaves no orphan symbols/edges.
- NFR-DATA-02 Re-index is single source of truth (no stale accretion).
- NFR-DATA-03 Dimension/model change requires re-ingest; mixing prevented/flagged.

**Observability (`NFR-OBS`)**
- NFR-OBS-01 Readiness fails when DB down.
- NFR-OBS-02 Metrics expose ingest counts, query latency, chunking-service failures.
- NFR-OBS-03 Scope-denial events auditable without logging secrets.

---

## 9. Edge / negative / robustness

- EDGE-01 Empty repo / zero supported files → 200 with zeros.
- EDGE-02 Duplicate symbol names (intra/inter file) → stable ids, no loss.
- EDGE-03 Recursive & mutually-recursive calls → correct graph, no infinite loop.
- EDGE-04 Called-but-undefined symbol → unresolved edge retained (null `dstId`).
- EDGE-05 Size/line-count boundaries; file without trailing newline.
- EDGE-06 Very long identifiers/signatures (truncation boundary).
- EDGE-07 Non-ASCII / emoji in comments/strings/identifiers.
- EDGE-08 Path traversal in `root`/`path` (`../../etc`) → rejected/contained.
- EDGE-09 Concurrent `index/file` + `search` on same file → no torn reads.
- EDGE-10 `topK` > total symbols → returns all, no error.
- EDGE-11 Empty tsquery (stopwords/punctuation only) → vector side still returns.
- EDGE-12 Repeated identical queries → consistent results.

---

## 10. Test data, environments, criteria

**Test data:** synthetic fixtures (one per equivalence class) + real version-pinned OSS
repos for E2E; a gold Q&A set with expected symbol ids; adversarial inputs (injection,
binary, huge, unicode).

**Environments:**
- Unit/component: JVM + Testcontainers (ephemeral pgvector); parser service stubbed.
- Integration/E2E: full stack via docker-compose / ephemeral namespace; chunking service in
  both topologies (sidecar and separate service).
- NFR: scaled dataset generator; fault-injection proxy for DB/service.

**Entry criteria:** build green, migrations apply, seed loads.
**Exit criteria:** 100% P1 pass; RBAC (NFR-SEC) zero leaks; relevance ≥ thresholds; p95 ≤
SLA; no Sev-1/2 open.

**Traceability:** every capability (§1) → ≥1 case at unit + component + E2E; every endpoint
→ contract + negative case; RBAC → exhaustive matrix. Maintain a requirement→test matrix.

**Risk-based priority (P1 first):** RBAC isolation, incremental freshness, ranking quality
are P1 (silent leak / stale answers are highest impact); graph accuracy P2; performance P2
→ P1 at target scale.
