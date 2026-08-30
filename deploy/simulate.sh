#!/usr/bin/env bash
# Runs the whole menD workflow on a laptop with no credentials, no network and no ACU spend.
# GitHub and Devin are replaced by in-memory simulations; everything else is the production code.
set -euo pipefail

cd "$(dirname "$0")/.."
BASE=${BASE:-http://localhost:8080}

SPRING_PROFILES_ACTIVE=sandbox \
MEND_ENGINE_ENABLED=true \
MEND_POLLING_ENABLED=true \
DEVIN_API_KEY= GITHUB_APP_ID= \
  docker compose up -d --build

printf 'waiting for menD'
for _ in $(seq 1 90); do
  if curl -sf "$BASE/actuator/health" >/dev/null; then echo " — up"; break; fi
  printf '.'; sleep 2
done

echo
echo "Filing one simulated issue per scenario…"
curl -sf -X POST "$BASE/api/sandbox/issues/all" >/dev/null
curl -sf "$BASE/api/sandbox" | sed -n '1,40p' || true

cat <<EOF

Four issues are now in the pipeline. Watch them move at $BASE/pipeline (it refreshes itself):

  CLEAN_FIX         DISCOVERED → READY → RUNNING → PR_OPEN → VERIFYING → SUCCEEDED
  NOT_A_CANDIDATE   DISCOVERED → NOT_A_CANDIDATE   (no remediation session is ever created)
  UNVERIFIED        …→ VERIFYING → UNVERIFIED      (a pull request, but nothing could prove it)
  REVIEW_THEN_FIX   …→ PR_OPEN → CHANGES_REQUESTED → RUNNING → SUCCEEDED, and writes lessons

The whole set settles in about a minute. Then look at:

  $BASE/pipeline     the board, including the honest UNVERIFIED column
  $BASE/learnings    what the simulated reviewer taught menD
  $BASE/api/sandbox  every label, comment, pull request and review menD wrote

Play the reviewer yourself on any open pull request:

  curl -X POST $BASE/api/sandbox/pulls/<number>/request-changes \\
       -H 'content-type: application/json' \\
       -d '{"reviewer":"you","body":"add a test next to the component"}'

Logs:  docker compose logs -f mend
Stop:  docker compose down -v      (-v also throws away the simulated state)
EOF
