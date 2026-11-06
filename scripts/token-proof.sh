#!/bin/bash
# Reproducible proof, split into two HONEST claims measured separately:
#
#  A) TOKEN SAVING (structural, independent of ranking): to work on a known symbol, an AI with
#     a locator reads only that symbol's span; without one it opens the whole file. We measure
#     span-tokens vs whole-file-tokens for a gold set of real symbols (authoritative line ranges
#     fetched live from code-memory). Tokens = bytes/4 (proxy; the ratio is the point).
#
#  B) RANKING PRECISION (honest about the offline hash embeddings): for a natural-language task,
#     does /api/context surface the gold symbol in top-5? Reported as hits/total — NOT hidden.
#
#   bash scripts/token-proof.sh
set -uo pipefail
CM="${CM:-http://localhost:8080}"
RAGAPP_DIR="${RAGAPP_DIR:-/local/IdeaProjects/rag-app}"
OUT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/docs/EVIDENCE.md"
export CM RAGAPP_DIR OUT

python3 - <<'PY'
import json, os, urllib.request
CM=os.environ["CM"]; RAG=os.environ["RAGAPP_DIR"]; OUT=os.environ["OUT"]

# gold: (natural-language task, exact symbol_path, what we actually want)
GOLD=[
 ("endpoint that returns matching chunks without invoking the LLM", "RetrieveController.retrieve"),
 ("answer a user question grounded in ingested documents",          "ChatController.chat"),
 ("combine vector similarity with full-text search",                "HybridPgDocumentStore.search"),
 ("reject callers that exceed the request rate limit",              "RateLimitInterceptor.preHandle"),
]

def post(path, obj):
    req=urllib.request.Request(CM+path, json.dumps(obj).encode(), {"content-type":"application/json"})
    with urllib.request.urlopen(req, timeout=30) as r: return json.load(r)

def tok(n): return (n+3)//4

def locator(symbol):
    for r in post("/api/search", {"query":symbol,"agent":"dev","topK":10})["results"]:
        if r["symbol"]==symbol: return r
    return None

rows=[]; g_tot=b_tot=0; hits=0
for task, sym in GOLD:
    loc=locator(sym)
    if not loc: continue
    path=os.path.join(RAG, loc["file"]); data=open(path,"rb").read()
    lines=data.split(b"\n"); s,e=loc["lines"]
    span=b"\n".join(lines[max(0,s-1):e])
    g=len(span); b=len(data); g_tot+=g; b_tot+=b
    red=100*(b-g)/b if b else 0
    # ranking precision: does the NL task surface the gold symbol in top-5?
    top5=[r["symbol"] for r in post("/api/context", {"task":task,"agent":"dev","topK":5})["results"]]
    hit = sym in top5; hits+=1 if hit else 0
    rows.append((sym, loc["file"].split("/")[-1]+" "+str(loc["lines"]), tok(g), tok(b), red, "hit" if hit else "miss"))

overall=100*(b_tot-g_tot)/b_tot if b_tot else 0
n=len(rows)

def md():
    L=["# Evidence\n",
       "Live against the rag-app index. Two claims, measured separately and honestly.\n",
       "## A. Token saving — read the symbol span, not the whole file",
       "For each **known** symbol: GROUNDED = tokens of its cited span; BASELINE = tokens of the whole "
       "file an AI opens to find it without a locator. Tokens = bytes/4.\n",
       "| symbol | locator | grounded tok | baseline tok | reduction |",
       "|---|---|---:|---:|---:|"]
    for sym,loc,g,b,red,_ in rows:
        L.append("| `%s` | %s | %d | %d | **%.0f%%** |"%(sym,loc,g,b,red))
    L.append("| **TOTAL** | | **%d** | **%d** | **%.0f%%** |"%(tok(g_tot),tok(b_tot),overall))
    L.append("\n**→ %.0f%% fewer context tokens** to work on the same code, and it is structural "
             "(span vs file), so it does not depend on ranking quality.\n"%overall)
    L.append("## B. Ranking precision — honest about the offline embeddings")
    L.append("For a natural-language task, did `/api/context` top-5 contain the gold symbol?\n")
    L.append("| task | gold symbol | top-5 |\n|---|---|:--:|")
    for (task,sym),r in zip(GOLD, rows):
        L.append("| %s | `%s` | %s |"%(task, sym, r[5]))
    L.append("\n**Precision@5 = %d/%d.** Ranking is the weak part: embeddings here are a pure-Java "
             "offline **hash** (the documented weakest signal). Full-text makes *exact-name* lookups "
             "reliable, but conceptual recall is poor. Fix = swap a code embedding model via the "
             "`EmbeddingProvider` port — the token saving in (A) is unaffected either way.\n"%(hits,n))
    L.append("## Reproduce\n```\nbash scripts/token-proof.sh\n```\n"
             "Reads real files on disk + live locator ranges. `/api/context` and rag-app "
             "`/api/retrieve` invoke **no LLM** (retrieve-only): these are context tokens, not generation.")
    return "\n".join(L)

text=md(); os.makedirs(os.path.dirname(OUT),exist_ok=True); open(OUT,"w").write(text+"\n")
print(text); print("\nwritten: "+OUT)
PY
