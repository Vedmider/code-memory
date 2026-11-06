# Grounded protocol — how/when/what-order the AI uses the apps

This is the single source of truth the AI follows for refactor/analysis tasks on `rag-app`
or `code-memory`. The order is fixed and executed by `scripts/grounded-locate.sh`, so it
cannot be done out of order. If the apps are unhealthy the command prints `FALLBACK` and the
AI uses ordinary flow (read/grep/edit) — an unstable app never blocks work.

## WHEN
Any "refactor X" / "analyse X" / "where is X" task on rag-app or code-memory. For trivial or
already-known files, skip straight to editing (ordinary flow).

## WHAT ORDER (fixed)
1. **Ground** — `scripts/grounded-locate.sh "<task>"`
   - preflight both apps → else FALLBACK (ordinary flow)
   - rag-app `/api/retrieve` → concept / why / where (+ repo explanation docs)
   - code-memory `/api/context` → exact locators (file+lines) + callers/tests
   - If hits look off-topic (conceptual query, weak embeddings) → retry with an **exact symbol
     name** (full-text is reliable) or fall back to a direct read.
2. **Read only the cited spans** — `read_file` with the locator's `offset/limit`. Do not open
   whole files. (This is where the token saving is realised.)
3. **Refactor** — edit the real files. For edits you may also read the file's import header.
4. **Gate** — `mvn -B verify` in the target repo. MUST be green before touching the running app.
5. **Redeploy** — `scripts/improve-loop.sh <repo> redeploy` (stop → rebuild image → start).
6. **Measure** — `scripts/context-ledger.sh` (tokens this iteration).
7. **Loop** — next task.

## Continuous improvement
`scripts/improve-loop.sh <repo> <step>` wraps steps 1,4,5,6 as discrete subcommands; the AI
performs step 2–3 (the refactor) between `ground` and `gate`. Never auto-commit/push — the
human gate stays. Never redeploy on red (`gate` must pass first).

## Rules
- rag-app = concept/docs (natural language). code-memory = exact locators + graph + sync.
- Exact-name queries are reliable; conceptual ranking is weak (offline hash embeddings).
- Read spans, not files. Scope with `agent`/`X-Roles`.
- Self-modification: when refactoring code-memory itself, finish refactor+gate first, then
  redeploy between iterations (don't tear down the instance mid-call).
