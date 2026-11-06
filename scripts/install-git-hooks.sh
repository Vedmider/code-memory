#!/bin/bash
# Install git hooks that push a sync to code-memory on every history-changing event, so the
# index updates immediately (the server read-gate is the backstop if a hook is missing).
#
# Usage:
#   scripts/install-git-hooks.sh <repo-worktree> <repo-id> [server-url]
# Example:
#   scripts/install-git-hooks.sh /local/IdeaProjects/myapp myapp http://localhost:8080
set -euo pipefail

WORKTREE="${1:?usage: install-git-hooks.sh <repo-worktree> <repo-id> [server-url]}"
REPO_ID="${2:?missing repo-id}"
SERVER="${3:-http://localhost:8080}"

HOOKS_DIR="$(cd "$WORKTREE" && git rev-parse --git-path hooks)"
ROOT="$(cd "$WORKTREE" && git rev-parse --show-toplevel)"
mkdir -p "$HOOKS_DIR"

write_hook() {
  local name="$1" path="$HOOKS_DIR/$1"
  cat > "$path" <<EOF
#!/bin/bash
# code-memory auto-sync ($name) — computes the delta server-side from git.
curl -fsS -m 10 -X POST "$SERVER/api/sync" \\
  -H 'content-type: application/json' \\
  -d '{"repo":"$REPO_ID","root":"$ROOT"}' >/dev/null 2>&1 || true
EOF
  chmod +x "$path"
  echo "installed $path"
}

for h in post-commit post-merge post-checkout post-rewrite; do
  write_hook "$h"
done
echo "done. repo='$REPO_ID' root='$ROOT' server='$SERVER'"
