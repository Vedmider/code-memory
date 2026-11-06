#!/bin/bash
# THE grounded-context command. Runs the app APIs in the ONE correct order and prints a
# context block the AI then acts on. If anything is unhealthy it prints FALLBACK and the AI
# uses ordinary flow — so an unstable app never blocks work.
#
#   scripts/grounded-locate.sh "<natural-language task>"
#
# Order (fixed, not up to the AI):
#   0. preflight: both apps healthy?            -> else FALLBACK
#   1. rag-app  /api/retrieve  (concept/why/where, incl. repo explanation docs)
#   2. code-memory /api/context (exact locators: file+lines, + callers/tests)
set -uo pipefail
RAG="${RAG_URL:-http://localhost:18080}"
CM="${CM_URL:-http://localhost:8080}"
TASK="${1:?usage: grounded-locate.sh \"<task>\"}"

health() { [ "$(curl -s -o /dev/null -w '%{http_code}' -m 3 "$1/actuator/health" 2>/dev/null)" = "200" ]; }

echo "== grounded-locate: \"$TASK\" =="
if ! health "$RAG" || ! health "$CM"; then
  echo "FALLBACK: apps not both healthy (rag-app=$RAG code-memory=$CM) -> use ORDINARY flow (read/grep/edit directly)."
  exit 3
fi

echo "--- 1. rag-app /retrieve (concept + docs) ---"
curl -sS -m 20 -XPOST "$RAG/api/retrieve" -H 'content-type: application/json' -H 'X-Roles: engineering' \
  -d "{\"query\":$(printf '%s' "$TASK" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))'),\"topK\":3}" \
  | python3 -c 'import sys,json
d=json.load(sys.stdin)
if not d: print("  (no docs — consider loading/generating repo explanation docs into rag-app)")
for x in d: print("  [%s] %s" % (x.get("source"), x.get("text","")[:160]))'

echo "--- 2. code-memory /context (exact locators) ---"
curl -sS -m 20 -XPOST "$CM/api/context" -H 'content-type: application/json' \
  -d "{\"task\":$(printf '%s' "$TASK" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))'),\"agent\":\"dev\",\"topK\":5}" \
  | python3 -c 'import sys,json
d=json.load(sys.stdin)
for r in d["results"]:
    print("  %-44s %s %s" % (r["symbol"], r["file"], r["lines"]))
    for c in d.get("callers",{}).get(r["id"],[])[:3]: print("      <- called by", c)'

echo "--- NEXT (AI): read ONLY the cited file:line spans, refactor, then: mvn verify -> dev-local.sh rebuild ---"
