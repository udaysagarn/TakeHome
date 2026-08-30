#!/usr/bin/env bash
# The single entry point: procures the credentials menD needs, writes .env, and starts the stack.
# menD only runs live — Devin writes real code and menD writes to a real repository — so this script
# refuses to start without a complete .env. Step-by-step instructions for each credential, including
# the GitHub App permissions, are in docs/CREDENTIALS.md.
set -euo pipefail

cd "$(dirname "$0")/.."
ENV_FILE=${ENV_FILE:-.env}

REQUIRED=(DEVIN_API_KEY DEVIN_ORG_ID GITHUB_APP_ID GITHUB_APP_INSTALLATION_ID
          GITHUB_APP_PRIVATE_KEY GITHUB_WEBHOOK_SECRET)

prompt_for() {
  case $1 in
    DEVIN_API_KEY)               echo "Devin service-user API key — app.devin.ai/settings/api-keys, starts with cog_ or apk_";;
    DEVIN_ORG_ID)                echo "Devin organisation id — the org-… in any app.devin.ai/org/ URL";;
    GITHUB_APP_ID)               echo "GitHub App ID — github.com/settings/apps → your app";;
    GITHUB_APP_INSTALLATION_ID)  echo "GitHub App installation id — the number ending github.com/settings/installations/…";;
    GITHUB_APP_PRIVATE_KEY)      echo "Path to the GitHub App private key .pem (its contents are inlined into $ENV_FILE)";;
    GITHUB_WEBHOOK_SECRET)       echo "GitHub webhook secret — press enter to generate one; the poller works without webhooks";;
  esac
}

# The value already recorded in $ENV_FILE, if any. docker compose lets the last assignment win, so
# read the last one here too: a value appended below .env.example's blank placeholder has been
# supplied, and a blank appended below a value has been taken away.
value_of() {
  [[ -f $ENV_FILE ]] || return 0
  sed -n "s/^$1=//p" "$ENV_FILE" | tail -1 | sed 's/^"//;s/"$//'
}

missing() {
  local name
  for name in "${REQUIRED[@]}"; do
    [[ -n $(value_of "$name") ]] || echo "$name"
  done
}

# .env is read literally by docker compose and by Spring: nothing in it is expanded, so a value is
# written as one double-quoted line and a multi-line private key keeps its newlines as \n escapes —
# the shape .env.example describes and GitHubCredentials accepts.
write_var() {
  local name=$1 value=$2 escaped line
  escaped=${value//\\/\\\\}
  escaped=${escaped//\"/\\\"}
  escaped=${escaped//$'\n'/\\n}
  if grep -q "^$name=" "$ENV_FILE"; then
    local out=() seen=""
    while IFS= read -r line; do
      if [[ $line == "$name="* ]]; then
        if [[ -n $seen ]]; then
          continue                          # collapse duplicates onto the first assignment
        fi
        line="$name=\"$escaped\""
        seen=yes
      fi
      out+=("$line")
    done <"$ENV_FILE"
    printf '%s\n' "${out[@]}" >"$ENV_FILE"
  else
    printf '%s="%s"\n' "$name" "$escaped" >>"$ENV_FILE"
  fi
}

ask() {
  local name=$1 answer
  echo
  echo "$(prompt_for "$name")"
  if ! read -r -p "  $name: " answer; then
    echo "  no input available — $name has to be supplied interactively or in $ENV_FILE" >&2
    exit 1
  fi
  case $name in
    GITHUB_APP_PRIVATE_KEY)
      answer=${answer/#\~/$HOME}
      if [[ -f $answer ]]; then
        answer=$(cat "$answer")
      elif [[ $answer != *"PRIVATE KEY-----"* ]]; then
        echo "  not a readable .pem path and not a PEM key — see docs/CREDENTIALS.md, section 2" >&2
        return 1
      fi
      ;;
    GITHUB_WEBHOOK_SECRET)
      if [[ -z $answer ]]; then
        answer=$(openssl rand -hex 32)
        echo "  generated a random secret"
      fi
      ;;
    DEVIN_API_KEY)
      if [[ $answer != cog_* && $answer != apk_* ]]; then
        echo "  a Devin key starts with cog_ or apk_ — see docs/CREDENTIALS.md, section 1" >&2
        return 1
      fi
      ;;
  esac
  if [[ -z $answer ]]; then
    echo "  $name cannot be empty" >&2
    return 1
  fi
  write_var "$name" "$answer"
}

gaps=$(missing)
if [[ -n $gaps ]]; then
  cat <<EOF
menD needs a Devin service-user key and a GitHub App before it can remediate anything.
docs/CREDENTIALS.md creates both, step by step, in about fifteen minutes. The answers go
into $ENV_FILE, which is gitignored and must stay that way.

Missing: $(echo "$gaps" | tr '\n' ' ')
EOF
  [[ -f $ENV_FILE ]] || cp .env.example "$ENV_FILE"
  for name in $gaps; do
    until ask "$name"; do :; done
  done
fi

# The registry seeds itself from these on first boot; keep them if the operator changed them.
[[ -n $(value_of MEND_REPO) ]] || write_var MEND_REPO udaysagarn/superset
[[ -n $(value_of MEND_ENGINE_ENABLED) ]] || write_var MEND_ENGINE_ENABLED true
[[ -n $(value_of MEND_POLLING_ENABLED) ]] || write_var MEND_POLLING_ENABLED true

gaps=$(missing)
if [[ -n $gaps ]]; then
  echo "still missing: $(echo "$gaps" | tr '\n' ' ')" >&2
  exit 1
fi

# Compose publishes ${PORT:-8080}, taking PORT from the environment or from $ENV_FILE, so the health
# probe has to follow it rather than assume 8080.
PORT=${PORT:-$(value_of PORT)}
BASE=${BASE:-http://localhost:${PORT:-8080}}

docker compose --env-file "$ENV_FILE" up -d --build

printf 'waiting for menD'
for _ in $(seq 1 90); do
  if curl -sf "$BASE/actuator/health" >/dev/null; then
    echo " — up"
    echo "menD is running at $BASE"
    exit 0
  fi
  printf '.'; sleep 2
done

echo " — menD never became healthy" >&2
docker compose --env-file "$ENV_FILE" logs mend >&2
exit 1
