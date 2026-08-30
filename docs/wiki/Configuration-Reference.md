# Configuration reference

Everything is a Spring Boot property under `mend.*`, and the ones you are likely to change are bound
to environment variables. Copy `.env.example` to `.env` and fill it in; never commit the filled-in
file.

## Credentials

| Variable | Property | Default | Notes |
|---|---|---|---|
| `DEVIN_API_KEY` | `mend.devin.api-key` | — | Required for scoping and remediation |
| `DEVIN_ORG_ID` | `mend.devin.org-id` | — | `org-…` |
| `DEVIN_API_URL` | `mend.devin.base-url` | `https://api.devin.ai` | |
| `GITHUB_APP_ID` | `mend.github.app.app-id` | — | |
| `GITHUB_APP_INSTALLATION_ID` | `mend.github.app.installation-id` | — | |
| `GITHUB_APP_PRIVATE_KEY` | `mend.github.app.private-key` | — | Whole PEM (newlines or `\n` escapes), or the path of the `.pem`, or the file base64-encoded |
| `GITHUB_TOKEN` | `mend.github.token` | — | Fallback for local use |
| `GITHUB_WEBHOOK_SECRET` | `mend.github.webhook-secret` | — | Empty disables signature checking (local only) |
| `GITHUB_API_URL` | `mend.github.api-url` | `https://api.github.com` | GHES-friendly |

## Ingestion

| Variable | Property | Default |
|---|---|---|
| `MEND_REPO` | `mend.github.repo` | `udaysagarn/superset` — the default repository, seeded into the registry on first boot |
| `MEND_REPOS` | `mend.github.repos` | Comma-separated list of repositories to seed; defaults to `MEND_REPO` |
| `MEND_TRIGGER_LABEL` | `mend.github.trigger-label` | `menD:fix` (per-repository overridable) |
| `MEND_POLLING_ENABLED` | `mend.github.polling-enabled` | `true` |
| `MEND_POLL_INTERVAL` | `mend.github.poll-interval` | `PT30S` |
| — | `mend.github.comments-enabled` | `true` |
| — | `mend.github.*-label` | `menD:in-progress`, `menD:pr-open`, `menD:done`, `menD:unverified`, `menD:needs-human`, `menD:not-a-candidate`, `menD:changes-requested` |

## The gate

| Variable | Property | Default |
|---|---|---|
| `MEND_MIN_CONFIDENCE` | `mend.triage.min-confidence` | `0.7` |
| — | `mend.triage.min-body-length` | `60` |
| — | `mend.triage.label-denylist` | `question, discussion, epic, wontfix, invalid` |
| — | `mend.triage.max-files-in-scope` | `25` |

## The engine

| Variable | Property | Default | Meaning |
|---|---|---|---|
| `MEND_ENGINE_ENABLED` | `mend.engine.enabled` | `true` | `false` stops every loop: the dashboard serves, nothing is dispatched. Read at startup only, and the pause switch cannot lift it |
| `MEND_RECONCILE_INTERVAL` | `mend.engine.reconcile-interval` | `PT15S` | |
| — | `mend.engine.context-interval` | `PT60S` | Profile refresh pass |
| `MEND_MAX_CONCURRENT` | `mend.engine.max-concurrent-sessions` | `4` | Devin sessions in flight |
| `MEND_MAX_ATTEMPTS` | `mend.engine.max-attempts` | `2` | Remediation attempts before escalating |
| — | `mend.engine.max-nudges` | `3` | Nudges to a blocked session |
| — | `mend.engine.nudge-after` | `PT10M` | |
| — | `mend.engine.session-timeout` | `PT3H` | |
| `MEND_LEASE_DURATION` | `mend.engine.lease-duration` | `PT2M` | How long a claim survives without a heartbeat |
| `MEND_HEARTBEAT_INTERVAL` | `mend.engine.heartbeat-interval` | `PT30S` | |
| `MEND_CRITERIA_ETA` | `mend.engine.criteria-eta` | `PT15M` | Predicted completion stamped on a claim |
| `MEND_VERIFY_ETA` | `mend.engine.verify-eta` | `PT30M` | |

## Budgets

| Variable | Property | Default |
|---|---|---|
| `MEND_CRITERIA_ACU` | `mend.devin.criteria-acu-limit` | `3` |
| `MEND_REMEDIATION_ACU` | `mend.devin.remediation-acu-limit` | `10` |
| — | `mend.verify.verifier-acu-limit` | `3` |
| — | `mend.learning.retrospective-acu-limit` | `2` |
| — | `mend.devin.dry-run` | `false` — build prompts, create no sessions |

## Verification

| Property | Default | Meaning |
|---|---|---|
| `mend.verify.contract-workflow` | `mend-verify.yml` | Workflow dispatched in the target repository |
| `mend.verify.contract-check-prefix` | `menD` | Check runs with this prefix count as the contract tier |
| `mend.verify.verifier-session-enabled` | `true` | `false` leaves only real CI, so everything else is honestly `UNVERIFIED` |
| `mend.verify.tier-timeout` | `PT45M` | How long to wait for a tier before giving up on it |

## Learning

| Property | Default |
|---|---|
| `mend.learning.review-poll-interval` | `PT2M` |
| `mend.learning.max-review-rounds` | `3` |
| `mend.learning.retrospective-enabled` | `true` |
| `mend.learning.max-lessons-in-prompt` | `12` |
| `mend.learning.min-applications-before-retiring` | `4` |

## Platform

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | |
| `MEND_DB_URL` | `jdbc:h2:file:./data/mend;AUTO_SERVER=TRUE` | Point at Postgres for anything shared |
| `MEND_DB_USER` / `MEND_DB_PASSWORD` | `sa` / empty | |

Actuator exposes `health`, `info`, `metrics` and `prometheus`, tagged
`application=mend-orchestrator`.

## Starting it

```bash
# The one entry point: procures credentials, writes .env, builds and starts
./deploy/setup.sh

# Dashboard only: the engine and the poller off, so no issue is dispatched and no ACU is spent
MEND_ENGINE_ENABLED=false MEND_POLLING_ENABLED=false docker compose up -d
```

## Pausing a running instance

`MEND_ENGINE_ENABLED` is startup configuration. To stop the spending on an instance that is already
up, use the pause switch in the navigation, or the same thing over the API:

```bash
curl -X POST 'localhost:8080/api/engine?paused=true&reason=demo%20over&actor=uday'
curl localhost:8080/api/engine     # {"paused":true,"off":false,"reason":"demo over", ...}
curl -X POST 'localhost:8080/api/engine?paused=false'
```

A pause holds only the steps that would create a Devin session — triage, dispatch, a retry, a
repository profile. Sessions already dispatched keep being polled and finished, because they have
been paid for either way, and an issue labelled while paused is still recorded and waits on the
board. The pause is stored in the database, so it survives a restart, and
`MEND_ENGINE_ENABLED=false` outranks it: the switch cannot start an engine that configuration
disabled.

### menD pauses itself when a credential cannot be used

A missing or refused credential stops the engine the same way, with the reason on the paused strip:
no GitHub App, no Devin key, a Devin key Devin answered `401`/`403` to, or a GitHub App that is
refused access to every registered repository. Dispatching against a credential that does not work
buys nothing, and an alarm nobody is watching does not stop a poller.

Recovery is deliberate: menD only learns a credential works by using it, so it does not resume by
itself once the variable is fixed — an operator resumes. Resuming while the problem is still there
is respected rather than overruled on the next tick; a *different* failure pauses menD again.
