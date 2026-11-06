# Using the two-app solution (for an AI agent)

Two retrieve-only services. **You are the only LLM** — neither app calls an LLM; they return
grounding so you spend fewer tokens.

- **rag-app** (`:18080`) — *static knowledge*: conventions, architecture, skills. `POST /api/retrieve`
  → `[{text, source, score}]`. Treat as hints.
- **code-memory** (`:8080`) — *live code*: `POST /api/search` and `POST /api/context` return
  **locators** `{file, symbol, lines:[s,e], score}` (+ `callers`/`tests`), never bodies. You read
  the fresh bytes at `file`+`lines` yourself. Treat as truth.

## The loop (do this per coding task)
1. **Conventions** — `POST :18080/api/retrieve {"query":"<topic>"}` (optional `X-Roles` header).
2. **Locate** — `POST :8080/api/context {"task":"<what you need>","agent":"dev","topK":5}`.
3. **Read only the cited spans** — open each `file` and read lines `s..e` only. This is where the
   ~76% token saving comes from (see EVIDENCE.md). Do **not** open whole files.
4. **Verify (locator-truth)** — confirm the `symbol` actually appears in the span before trusting it.
5. **Edit** the real files on disk.
6. **Commit** — the read-gate re-indexes only the changed files on your next query (uncommitted
   edits are also picked up). No manual re-index needed.
7. **Re-query** to confirm the change is reflected.

## Exact calls
```
# static conventions (no LLM)
curl -XPOST localhost:18080/api/retrieve -H 'content-type: application/json' \
  -H 'X-Roles: engineering' -d '{"query":"how should I write code comments?","topK":3}'

# code locators + blast radius (no LLM)
curl -XPOST localhost:8080/api/context -H 'content-type: application/json' \
  -d '{"task":"the retry/backoff logic","agent":"dev","topK":5}'

# cold start / after big changes: index a repo by path (auto-installs git hooks)
curl -XPOST localhost:8080/api/index -H 'content-type: application/json' \
  -d '{"repo":"myrepo","root":"/abs/path/to/repo"}'
```

## Rules that matter
- **Known symbol → use its name as the query.** Full-text makes exact-name lookups reliable.
- **Conceptual query → cross-check.** Ranking uses offline hash embeddings and is weak
  (EVIDENCE.md: precision@5 ≈ 1/4); if the top hits look off-topic, fall back to a direct read.
- **Never retrieve bodies.** Always read fresh bytes from the locator — the index can lag a span
  by a few lines after edits; the file on disk is authoritative.
- **Scope with `agent`/`X-Roles`** so you only see what the role may (e.g. `qa` doesn't get deploy/secrets).
- **Small/obvious files:** skip the tools and just read — the overhead isn't worth it.
