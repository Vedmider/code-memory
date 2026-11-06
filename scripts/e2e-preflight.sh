#!/bin/bash
# Pre-start detector for the code-memory + rag-app e2e stack. Runs BEFORE anything starts and
# fails fast with actionable reasons, catching the real traps: missing/ stale images (an old
# image silently lacks new endpoints), ports already taken, and missing local-embedding model.
#
#   bash scripts/e2e-preflight.sh [target-repo-path]
#
# Exit 0 = ready to start. Exit 1 = blocking problem (details printed). WARN never blocks.
set -uo pipefail

CODEMEM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RAGAPP_DIR="${RAGAPP_DIR:-/local/IdeaProjects/rag-app}"
CODEMEM_PORT="${CODEMEM_PORT:-8080}"
RAGAPP_PORT="${RAGAPP_PORT:-18080}"
CODEMEM_IMAGE="${APP_IMAGE:-code-memory:0.1.0}"
CHUNKING_IMAGE="${CHUNKING_IMAGE:-code-memory-chunking:0.1.0}"
RAGAPP_IMAGE="${RAGAPP_IMAGE:-rag-app:0.1.0}"
MODEL="$RAGAPP_DIR/src/main/resources/onnx/all-MiniLM-L6-v2/model.onnx"
TARGET_REPO="${1:-}"

fails=0
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
warn() { printf '  \033[33mWARN\033[0m  %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fails=$((fails+1)); }

need_cmd() { command -v "$1" >/dev/null 2>&1 && ok "tool: $1" || bad "missing tool: $1 ($2)"; }

port_listening() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && { exec 3>&- 3<&-; return 0; } || return 1; }

# An app port is fine only if free, OR already serving OUR healthy app (= already running).
check_app_port() {
  local name="$1" port="$2"
  if ! port_listening "$port"; then ok "$name port $port free"; return; fi
  if [ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "http://localhost:$port/actuator/health" 2>/dev/null)" = "200" ]; then
    warn "$name port $port already serving a healthy app (already running?)"
  else
    bad "$name port $port occupied by something else — free it before starting"
  fi
}

# Image must exist AND not be older than the source (stale image = missing new endpoints).
check_image() {
  local img="$1" srcdir="$2"
  if ! docker image inspect "$img" >/dev/null 2>&1; then
    warn "image $img absent — it will be built on first start"; return
  fi
  local img_epoch src_epoch
  img_epoch=$(date -d "$(docker image inspect -f '{{.Created}}' "$img")" +%s 2>/dev/null || echo 0)
  src_epoch=$(find "$srcdir/src" "$srcdir/pom.xml" "$srcdir/go.mod" "$srcdir/main.go" -type f -printf '%T@\n' 2>/dev/null \
              | cut -d. -f1 | sort -nr | head -1)
  if [ -n "$src_epoch" ] && [ "$src_epoch" -gt "$img_epoch" ]; then
    bad "image $img is OLDER than its source — rebuild (it won't reflect latest code)"
  else
    ok "image $img present and newer than source"
  fi
}

echo "== e2e preflight =="
echo "- tooling"
need_cmd docker "container runtime"; need_cmd curl "health/API checks"; need_cmd python3 "json"
if command -v docker >/dev/null 2>&1; then
  docker info >/dev/null 2>&1 && ok "docker daemon reachable" || bad "docker daemon not reachable"
fi

echo "- images (present + not stale)"
check_image "$CODEMEM_IMAGE" "$CODEMEM_DIR"
check_image "$CHUNKING_IMAGE" "$CODEMEM_DIR/chunking-service"
check_image "$RAGAPP_IMAGE" "$RAGAPP_DIR"

echo "- ports"
check_app_port "code-memory" "$CODEMEM_PORT"
check_app_port "rag-app" "$RAGAPP_PORT"

echo "- rag-app local embeddings"
[ -s "$MODEL" ] && ok "ONNX model present ($(du -h "$MODEL" | cut -f1))" \
  || bad "ONNX model missing ($MODEL) — rag-app local embeddings won't load"

if [ -n "$TARGET_REPO" ]; then
  echo "- target repo"
  if git -C "$TARGET_REPO" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    ok "target repo is a git work tree: $TARGET_REPO"
  else
    warn "target repo is not a git work tree: $TARGET_REPO (git read-gate/hooks disabled there)"
  fi
fi

echo
if [ "$fails" -eq 0 ]; then
  echo -e "\033[32mPREFLIGHT OK\033[0m — safe to start."; exit 0
else
  echo -e "\033[31mPREFLIGHT FAILED\033[0m — $fails blocking issue(s) above."; exit 1
fi
