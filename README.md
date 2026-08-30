# menD — event-driven issue remediation control plane

menD turns a GitHub label into a merged pull request. It watches `udaysagarn/superset` for issues labelled
`menD:fix`, refuses to spend anything on issues that have no machine-checkable definition of done, dispatches
one Devin session per issue that survives that gate, supervises the session until a pull request has green CI,
and reports throughput, success rate and ACU cost on a live dashboard.

Devin is the execution engine. menD is the control plane around it: candidacy, budgets, retries, verification and
the audit trail.

```
GitHub issue labelled menD:fix
        │  webhook  ·  or 30s poller (no ingress required)
        ▼
   Orchestrator ── deterministic pre-filter (free) ────────────► NOT_A_CANDIDATE
        │                                                        (labelled + explained on the issue)
        ├─ criteria in the issue body?  ──► gate
        └─ otherwise read-only Devin scoping session ──► gate ──► NOT_A_CANDIDATE
                                                          │
                                                        READY
                                                          ▼
                                        Devin remediation session (criteria in the prompt)
                                                          ▼
                                          PR_OPEN ──► VERIFYING ──► SUCCEEDED
                                                       CI red ──► nudge session ──► retry ──► NEEDS_HUMAN
```

## Why the criteria gate exists

An automation that opens a pull request for every issue is a liability. Before any remediation session is
created, an issue must have: a non-placeholder body, no denylisted label, bounded file scope, at least one
acceptance criterion, at least one verification command, no blocking unknowns, and confidence ≥ 0.7.

Criteria come from a fenced block a human wrote in the issue:

````markdown
```devin-criteria
{
  "is_candidate": true,
  "confidence": 0.9,
  "problem_restatement": "...",
  "acceptance_criteria": ["..."],
  "verification_commands": ["..."],
  "files_in_scope": ["..."],
  "risk": "low",
  "blocking_unknowns": [],
  "rationale": "..."
}
```
````

or, when absent, from a short read-only Devin *scoping* session with a tight ACU cap that returns the same
schema as structured output. If the gate fails, the issue is moved to `NOT_A_CANDIDATE`, labelled
`menD:not-a-candidate`, and commented on with the exact reasons and what a human would need to add. Adding
that detail and re-applying `menD:fix` re-enters the pipeline, so the gate teaches the team how to write
automatable issues rather than silently dropping them.

The accepted criteria then become the contract: they are embedded in the remediation prompt, the session's
structured output must assert each one with evidence, and CI is the independent check.

## Issue state machine

`DISCOVERED → CRITERIA_PENDING → READY → DISPATCHED → RUNNING/BLOCKED → PR_OPEN → VERIFYING → SUCCEEDED`,
with `NOT_A_CANDIDATE`, `FAILED`, `NEEDS_HUMAN` and `CANCELLED` as the other outcomes. The table in
`IssueState.canTransitionTo` is the single authority; `TaskService` is the only writer, and every transition is
persisted, audited in `task_event`, logged and metered. The reconciler is level-triggered, so a restart resumes
in-flight work from the database rather than losing it.

## Worker leases

Workers coordinate through the database, so menD scales past one replica without two workers ever
driving the same issue.

- **Claim.** A worker only advances a task after a conditional `UPDATE ... WHERE owner_id IS NULL OR
  lease_expires_at <= now`, which is atomic: in a race exactly one worker gets `1` row updated, the
  rest get `0` and move on.
- **Predicted completion.** The claim also writes `eta_at` — what the owner commits to, derived from
  the state it is in (scoping, session, verification). Once it passes, the task shows as overdue on
  the dashboard and in the logs; nothing silently sits forever.
- **Heartbeat.** `LeaseManager.heartbeat` extends every lease the worker holds every 30s, so a Devin
  call that outlives the 2 minute lease is not stolen from a healthy worker.
- **Death.** A worker that dies stops heartbeating; after `lease_expires_at` any other worker claims
  the task, increments `lease_takeovers`, and resumes from the persisted row — the Devin session id
  is already stored, so the new worker keeps supervising the same session rather than starting a new
  one. Ownership is per task, so a dead worker only stalls its own tasks for at most one lease.
- **Release.** Terminal states release the lease, as does a clean shutdown, so a rolling restart
  hands work over immediately instead of waiting for expiry.

## Components and where they run

| Component | Runs in |
|---|---|
| `ingest` — `POST /webhooks/github` (HMAC-SHA256 verified) | menD process |
| `ingest` — 30s issue poller, used when GitHub cannot reach menD | menD process |
| `triage` — `PreFilter`, `SuccessCriteriaService` (the gate) | menD process, calls the Devin API |
| `engine` — `Orchestrator`, `TaskService`, `Reconciler` | menD process |
| `web` — Thymeleaf + htmx dashboard, JSON API, markdown report | menD process |
| state store — H2 file (demo) or PostgreSQL (prod), same schema | alongside menD |
| remediation itself | Devin cloud (API v3, `POST/GET /v3/organizations/{org_id}/sessions`) — one session per issue, menD never runs the fix |
| pull requests, comments, labels | GitHub |

## Running it

```bash
export DEVIN_API_KEY=cog_...      # service-user key; never committed
export DEVIN_ORG_ID=org-...       # org the service user belongs to; scopes every v3 session route
export GITHUB_APP_ID=...          # GitHub App installed on the target repo
export GITHUB_APP_INSTALLATION_ID=...
export GITHUB_APP_PRIVATE_KEY="$(cat mend-bot.private-key.pem)"
export MEND_REPO=udaysagarn/superset
mvn spring-boot:run
```

menD acts as a GitHub App rather than as a person: it signs an RS256 JWT with the app's private key, exchanges
it for a one-hour installation token, and refreshes that token before it expires. Every label, comment and PR
is therefore attributable to the bot identity in the audit log, and the permissions (Issues: write, Pull
requests: write, Contents/Checks/Metadata: read) are scoped to the single installed repository. Both the
PKCS#1 key GitHub hands out and a PKCS#8 key are accepted. `GITHUB_TOKEN=ghp_...` remains as a fallback for
local development when no app is configured.

| Endpoint | Purpose |
|---|---|
| `/` | monitoring view |
| `/api/summary`, `/api/tasks`, `/api/tasks/{id}/events` | JSON read model |
| `/api/report` | markdown report for a leadership audience |
| `/actuator/prometheus` | `mend_issues{state}`, `mend_sessions_active`, `mend_time_to_pr`, `mend_transitions`, `mend_acu_budget` |
| `/webhooks/github` | GitHub `issues.labeled` events |

Configuration is environment-driven (`src/main/resources/application.yml`): ACU caps, concurrency, attempt and
nudge budgets, confidence threshold, label names, poll and reconcile intervals. Without `DEVIN_API_KEY` and
`DEVIN_ORG_ID` the control plane still runs and the gate still rejects, it just cannot create sessions.

Session creation is not idempotent at the API level; menD gets idempotency from the unique `(repo,
issue_number)` task row, so a replayed webhook or an overlapping poll never opens a second session.

## Monitoring view

The stylesheet reproduces Devin's own design tokens — `--bg-page`, `--bg-elevated`, `--text-primary`,
`--bg-accent-primary`, `--text-green/red/orange`, the `--shadow-L*` elevation scale — under Devin's
`.light` / `.dark` / `.high-contrast` class scheme, so the view is recognisably part of the product rather than
an approximation of it. It shows the KPI strip, the pipeline board, the run table (issue → criteria → session →
PR → CI), the exclusion panel with reasons, and the live state-transition stream.

The three numbers a VP should look at: **success rate of attempted issues**, **median time from label to pull
request**, and **ACU per successful remediation**. The exclusion panel is the honesty surface — it shows the
system declining work it cannot verify.

## Tests

```bash
mvn test
```

covers the transition table, the candidacy gate (placeholder bodies, denylisted labels, low confidence,
blocking unknowns, missing verification commands, human-authored criteria), the Devin API wire format against a
mock server, webhook signature verification, dashboard/report rendering, and the full pipeline against a mocked
Devin and GitHub — including the property that matters most: an issue only reaches a remediation session after
the criteria gate has passed.
