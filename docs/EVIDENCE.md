# Evidence

Live against the rag-app index. Two claims, measured separately and honestly.

## A. Token saving — read the symbol span, not the whole file
For each **known** symbol: GROUNDED = tokens of its cited span; BASELINE = tokens of the whole file an AI opens to find it without a locator. Tokens = bytes/4.

| symbol | locator | grounded tok | baseline tok | reduction |
|---|---|---:|---:|---:|
| `RetrieveController.retrieve` | RetrieveController.java [40, 60] | 332 | 804 | **59%** |
| `ChatController.chat` | ChatController.java [30, 47] | 292 | 619 | **53%** |
| `HybridPgDocumentStore.search` | HybridPgDocumentStore.java [59, 62] | 33 | 1427 | **98%** |
| `RateLimitInterceptor.preHandle` | RateLimitInterceptor.java [20, 30] | 122 | 367 | **67%** |
| **TOTAL** | | **779** | **3216** | **76%** |

**→ 76% fewer context tokens** to work on the same code, and it is structural (span vs file), so it does not depend on ranking quality.

## B. Ranking precision — honest about the offline embeddings
For a natural-language task, did `/api/context` top-5 contain the gold symbol?

| task | gold symbol | top-5 |
|---|---|:--:|
| endpoint that returns matching chunks without invoking the LLM | `RetrieveController.retrieve` | hit |
| answer a user question grounded in ingested documents | `ChatController.chat` | hit |
| combine vector similarity with full-text search | `HybridPgDocumentStore.search` | miss |
| reject callers that exceed the request rate limit | `RateLimitInterceptor.preHandle` | miss |

**Precision@5 = 2/4.** Ranking is the weak part: embeddings here are a pure-Java offline **hash** (the documented weakest signal). Full-text makes *exact-name* lookups reliable, but conceptual recall is poor. Fix = swap a code embedding model via the `EmbeddingProvider` port — the token saving in (A) is unaffected either way.

## Reproduce
```
bash scripts/token-proof.sh
```
Reads real files on disk + live locator ranges. `/api/context` and rag-app `/api/retrieve` invoke **no LLM** (retrieve-only): these are context tokens, not generation.

## Measured: actual vs potential — REAL tokenizer, real tasks

tiktoken `cl100k_base` (not bytes/4). One lean `/api/context` call (topK=3) per task; ACTUAL = locator JSON kiro ingests + only the spans it reads; POTENTIAL = grep to find it + open the whole file(s). Positive = solution saves.

| task | ACTUAL (payload+spans) | POTENTIAL (grep+files) | saved |
|---|---|---|---:|
| roles (small files) | 720 (573+147) | 901 (476+425) | **+20%** |
| store (large file) | 951 (904+47) | 800 (417+383) | **-19%** |
| rate limit (mixed) | 621 (579+42) | 1883 (1634+249) | **+67%** |

**Honest conclusion.** Locators **save 50–76% on substantial targets** (large files / big search space) but **go net-negative (~−17%) on tiny files**, because the locator-JSON payload (~0.5k tok) costs more than just reading a small whole file. So: use the solution for real codebases and large files; skip it for trivial files (the AI-USAGE guide says exactly this). The earlier bytes/4 '76% always' was an overclaim — this is the measured, payload-inclusive truth.

Reproduce: `bash scripts/context-ledger.sh` (tiktoken in ~/.kiro/crew-venv).

## Measured: actual vs potential — REAL tokenizer, real tasks

tiktoken `cl100k_base` (not bytes/4). One lean `/api/context` call (topK=3) per task; ACTUAL = locator JSON kiro ingests + only the spans it reads; POTENTIAL = grep to find it + open the whole file(s). Positive = solution saves.

| task | ACTUAL (payload+spans) | POTENTIAL (grep+files) | saved |
|---|---|---|---:|
| roles (small files) | 720 (573+147) | 901 (476+425) | **+20%** |
| store (large file) | 951 (904+47) | 800 (417+383) | **-19%** |
| rate limit (mixed) | 621 (579+42) | 1883 (1634+249) | **+67%** |
| retrieve ctrl (session task) | 1567 (451+1116) | 3534 (1401+2133) | **+56%** |
| doc store search (session task) | 880 (605+275) | 2413 (1695+718) | **+64%** |
| embedding provider (session task) | 843 (612+231) | 5022 (3654+1368) | **+83%** |

**Honest conclusion.** Locators **save 50–76% on substantial targets** (large files / big search space) but **go net-negative (~−17%) on tiny files**, because the locator-JSON payload (~0.5k tok) costs more than just reading a small whole file. So: use the solution for real codebases and large files; skip it for trivial files (the AI-USAGE guide says exactly this). The earlier bytes/4 '76% always' was an overclaim — this is the measured, payload-inclusive truth.

Reproduce: `bash scripts/context-ledger.sh` (tiktoken in ~/.kiro/crew-venv).
