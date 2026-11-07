# Skill: code-memory — fuzzy code recall across a repo

Provider-agnostic guidance for an AI agent on using **code-memory**. Reformat the wrapper for your
assistant; the body is the skill.

## What this app is

code-memory keeps an **AST-level symbol index** of a codebase (classes/methods/functions + a call
graph) current as code changes, and answers "where is the code for this?" with **hybrid retrieval
(vector + full-text + dependency-graph ranking)**, scoped per agent/role and per repo. Best when you
**don't know the exact name** and want the relevant area ranked for you. Multi-language, multi-repo.

Endpoints (via `scripts/apicall.py` or HTTP):
- `POST /api/search` `{query, repo, agent, topK}` → ranked `[{id, file, symbol, kind, signature,
  lines, score}]`.
- `POST /api/context` `{task, repo, agent, topK}` → ranked symbols + callers + tests for a task.
- `POST /api/index` `{repo, root, force}` / `POST /api/sync` → build / refresh the index
  (`force:true` = clean full reindex).

## When to USE it

- Fuzzy "where is the X logic?" when you don't have the exact identifier.
- "What are the most relevant / most central symbols for this task?" (graph-ranked).
- "Which symbols relate to this change, and what calls/tests touch them?" → `/api/context`.
- Recall across a large or multi-language repo where you want ranking, not raw matches.

## When NOT to use it

- You already know the exact symbol name → go straight to **context-mcp** `search_symbols` (don't
  spend a fuzzy recall).
- You need the exact source span / minimal tokens / uncommitted working-tree edits → **context-mcp**.
- A conceptual question answered by prose docs → **rag-app**.
- Pure natural-language intent with no code keywords → embeddings here are **hash-based**
  (lexical/structural), weak at semantics; prefer rag-app for concepts.
- Arbitrary text/regex, logs, config → **grep/ripgrep**.

## How to use well

1. Always pass `repo` to scope results; a shared store holds multiple repos.
2. If results look stale or incomplete, `index --force` (symbols and file-hashes can otherwise
   diverge — force guarantees a clean rebuild).
3. Use `/api/context` (not just `/api/search`) when you want callers+tests alongside the hits.
4. Treat ranking as fuzzy: confirm the exact target with context-mcp before editing.

## Fits with the other two apps

concept/docs → **rag-app** · fuzzy "where is the code" → **code-memory** · exact code + live edits →
**context-mcp**. In the unified setup, context-mcp's `recall` tool delegates here.
