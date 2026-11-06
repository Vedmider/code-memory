#!/bin/bash
# Continuous-improvement driver. Wraps the fixed-order protocol (docs/GROUNDED-PROTOCOL.md)
# as discrete steps the AI runs; the AI does the refactor itself between `ground` and `gate`.
# Never redeploys on red; never commits/pushes.
#
#   scripts/improve-loop.sh <repo> ground "<task>"   # steps 1-2: grounded context (fixed order)
#   scripts/improve-loop.sh <repo> gate              # step 4: mvn verify (MUST pass before redeploy)
#   scripts/improve-loop.sh <repo> redeploy          # step 5: stop -> rebuild image -> start
#   scripts/improve-loop.sh <repo> measure           # step 6: token ledger
#   <repo> = rag-app | code-memory
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="${1:?usage: improve-loop.sh <rag-app|code-memory> <ground|gate|redeploy|measure> [task]}"
STEP="${2:?missing step}"
MIRROR="${MIRROR:-armdocker.rnd.ericsson.se/dockerhub-ericsson-remote}"
case "$REPO" in
  rag-app)     DIR=/local/IdeaProjects/rag-app ;;
  code-memory) DIR=/local/IdeaProjects/code-memory ;;
  *) echo "unknown repo: $REPO"; exit 2 ;;
esac

case "$STEP" in
  ground)
    bash "$HERE/grounded-locate.sh" "${3:?missing task}" ;;
  gate)
    echo "== gate: mvn -B verify ($REPO) =="
    ( cd "$DIR" && source env.sh && mvn -B verify ) ;;
  redeploy)
    echo "== gate recheck before redeploy =="
    ( cd "$DIR" && source env.sh && mvn -B -q verify ) || { echo "RED — refusing to redeploy"; exit 1; }
    echo "== redeploy $REPO (stop -> rebuild -> start) =="
    if [ "$REPO" = "rag-app" ]; then
      ( cd "$DIR" && MIRROR="$MIRROR" EMBEDDING_PROVIDER=local bash scripts/dev-local.sh rebuild )
    else
      ( cd "$DIR" && DOCKER_BUILDKIT=1 docker build --build-arg REGISTRY="$MIRROR/library" \
          --secret id=m2settings,src="$HOME/.m2/settings.xml" -t code-memory:0.1.0 . )
      docker rm -f cmrag-app >/dev/null 2>&1
      docker run -d --name cmrag-app --network cmrag -p 8080:8080 \
        -e DB_URL=jdbc:postgresql://cmrag-pg:5432/codemem -e DB_USERNAME=codemem -e DB_PASSWORD=codemem \
        -v /local/IdeaProjects/rag-app:/repo:ro code-memory:0.1.0 >/dev/null
      for i in $(seq 1 60); do [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/actuator/health 2>/dev/null)" = "200" ] && { echo "code-memory UP"; break; }; sleep 3; done
      curl -sS -XPOST localhost:8080/api/index -H 'content-type: application/json' -d '{"repo":"rag-app","root":"/repo"}' >/dev/null
    fi ;;
  measure)
    bash "$HERE/context-ledger.sh" ;;
  *) echo "unknown step: $STEP"; exit 2 ;;
esac
