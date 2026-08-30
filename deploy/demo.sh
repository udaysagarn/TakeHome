#!/usr/bin/env bash
# Brings menD up on a laptop and walks the demo. Works with or without credentials.
set -euo pipefail

cd "$(dirname "$0")/.."
BASE=${BASE:-http://localhost:8080}

if [[ ! -f .env ]]; then
  echo "No .env found — starting in read-only mode (dashboard only, no GitHub or Devin calls)."
  cp .env.example .env
  sed -i.bak 's/^MEND_ENGINE_ENABLED=.*/MEND_ENGINE_ENABLED=false/;s/^MEND_POLLING_ENABLED=.*/MEND_POLLING_ENABLED=false/' .env
  rm -f .env.bak
fi

docker compose up -d --build

printf 'waiting for menD'
for _ in $(seq 1 90); do
  if curl -sf "$BASE/actuator/health" >/dev/null; then echo " — up"; break; fi
  printf '.'; sleep 2
done

cat <<EOF

menD is running.

  1. Product overview        $BASE/
  2. Register a repository   $BASE/repositories/new
  3. Pipeline board          $BASE/pipeline
  4. What reviewers taught    $BASE/learnings
  5. Leadership report       $BASE/api/report

To drive a live remediation: label a GitHub issue 'menD:fix' in a registered
repository. menD scopes it, writes acceptance criteria, dispatches Devin, waits
for independent verification, and reports back on the board.

Logs:  docker compose logs -f mend
Stop:  docker compose down          (state survives in the mend-data volume)
EOF
