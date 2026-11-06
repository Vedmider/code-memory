#!/bin/bash
# Local dev loop for code-memory. Brings the stack up as Docker containers (pgvector + app,
# plus the chunking service when multi-language parsing is wanted), smoke-tests the API by
# indexing THIS repo, then tears everything down. All data is ephemeral.
#
# Nothing to download first: embeddings are a built-in pure-Java hash. The only choice is
# whether you want Go/Python parsing (the chunking service):
#
#   bash scripts/dev-local.sh all            # pure-Java: build -> up -> test -> down
#   PARSING_MODE=multi bash scripts/dev-local.sh all   # add Go/Python chunking service
#
# Commands:
#   up | rebuild | test | logs | status | down | all
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=/dev/null
source "$REPO/scripts/config.sh"

COMPOSE="docker compose"
PROFILE=()
if [ "$PARSING_MODE" = "multi" ] || [ "${CHUNKING:-0}" = "1" ]; then
  PROFILE=(--profile chunking)
  export PARSING_MODE=multi
fi

dc() { ( cd "$REPO" && $COMPOSE "${PROFILE[@]}" "$@" ); }

build_images() {
  # Build app (and chunking, in multi) with the mirror registry. A Maven settings.xml, if
  # present, is passed as a BuildKit secret so the in-container build can reach a mirror.
  local secret=()
  [ -f "$HOME/.m2/settings.xml" ] && secret=(--secret id=m2settings,src="$HOME/.m2/settings.xml")
  echo "== build app image $APP_IMAGE (registry: $REGISTRY) =="
  ( cd "$REPO" && DOCKER_BUILDKIT=1 docker build --build-arg REGISTRY="$REGISTRY" \
      "${secret[@]}" -t "$APP_IMAGE" . )
  if [ "${#PROFILE[@]}" -gt 0 ]; then
    echo "== build chunking image $CHUNKING_IMAGE =="
    ( cd "$REPO/chunking-service" && DOCKER_BUILDKIT=1 docker build \
        --build-arg REGISTRY="$REGISTRY" -t "$CHUNKING_IMAGE" . )
  fi
}

up() {
  echo "== up: pgvector + app (parsing mode: $PARSING_MODE) =="
  build_images
  dc up -d
  echo "== waiting for app health =="
  for _ in $(seq 1 60); do
    [ "$(curl -sS -m 3 -o /dev/null -w '%{http_code}' "$BASE/actuator/health" 2>/dev/null)" = "200" ] && { ready=1; break; }
    sleep 3
  done
  if [ "${ready:-0}" != "1" ]; then
    echo "app did not become healthy:"; dc logs app 2>&1 | tail -40; exit 1
  fi
  echo "UP -> $BASE   (health: $(curl -sS "$BASE/actuator/health"))"
  echo "Try:  bash scripts/dev-local.sh test     Stop: bash scripts/dev-local.sh down"
}

test_api() {
  echo "== index this repo (mounted read-only at /workspace) =="
  curl -sS -m 120 -XPOST "$BASE/api/index" -H 'content-type: application/json' \
    -d '{"repo":"self","root":"/workspace"}'; echo

  echo "== search an exact symbol (full-text proves exact-token retrieval) =="
  curl -sS -m 30 -XPOST "$BASE/api/search" -H 'content-type: application/json' \
    -d '{"query":"PgLiterals textArray","agent":"all","topK":5}'; echo

  echo "== task context with graph enrichment (callers / tests) =="
  curl -sS -m 30 -XPOST "$BASE/api/context" -H 'content-type: application/json' \
    -d '{"task":"where is hybrid search built?","agent":"dev","topK":5}'; echo
}

status_fn() {
  dc ps
  curl -sS "$BASE/actuator/health" 2>/dev/null && echo
}

down() {
  echo "== tearing down + wiping data =="
  dc down -v --remove-orphans
  echo "DOWN — containers, network, and data removed."
}

run_all() {
  trap 'echo "=== DOWN ==="; down' EXIT
  echo "=== UP ===";     up
  echo "=== STATUS ==="; status_fn
  echo "=== TEST ===";   test_api
  echo "=== all steps completed; tearing down ==="
}

case "${1:-}" in
  up)      up ;;
  rebuild) dc down -v 2>/dev/null; build_images && up ;;
  test)    test_api ;;
  logs)    dc logs -f app ;;
  status)  status_fn ;;
  down)    down ;;
  all)     run_all ;;
  *)       echo "usage: $0 {all|up|rebuild|test|logs|status|down}  (set PARSING_MODE=multi for Go/Python)"; exit 2 ;;
esac
