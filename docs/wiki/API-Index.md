# API index

Every route menD serves, on one page.

## JSON API — `/api`

| Method | Route | Purpose | Page |
|---|---|---|---|
| `GET` | `/api/summary` | KPI block | [Reports](Reports-and-Metrics) |
| `GET` | `/api/states` | Count per state | [Polling](Polling-Task-Status) |
| `GET` | `/api/tasks` | All tasks, newest activity first | [Polling](Polling-Task-Status) |
| `GET` | `/api/tasks/{id}` | Full task detail, or `404` | [Polling](Polling-Task-Status) |
| `GET` | `/api/tasks/{id}/events` | Append-only state history | [Polling](Polling-Task-Status) |
| `POST` | `/api/tasks/{id}/cancel` | `{"state":"CANCELLED"}`, or `404` | [Triggering](Triggering-Issue-Resolution) |
| `POST` | `/api/issues/{number}/ingest?repo=` | Trigger remediation → `202 {"task":"owner/repo#n"}` | [Triggering](Triggering-Issue-Resolution) |
| `GET` | `/api/repositories` | Registered repositories | [Registering](Registering-a-Repository) |
| `POST` | `/api/repositories` | Register + validate `{"repo":"owner/name"}` | [Registering](Registering-a-Repository) |
| `POST` | `/api/repositories/{id}/validate` | Re-run access validation | [Registering](Registering-a-Repository) |
| `GET` | `/api/learnings` | `active`, `recommendedActions`, `retired` | [Learnings](Learnings-API) |
| `GET` | `/api/report` | Markdown digest (`text/markdown`) | [Reports](Reports-and-Metrics) |

## Webhooks

| Method | Route | Purpose |
|---|---|---|
| `POST` | `/webhooks/github` | Issues, pushes, pull requests, reviews — see [Webhooks](Webhooks) |

## Sandbox — `sandbox` profile only

| Method | Route | Purpose |
|---|---|---|
| `GET` | `/api/sandbox` | Scenarios and everything written to the fake GitHub |
| `POST` | `/api/sandbox/issues?scenario=&repo=` | File one simulated issue |
| `POST` | `/api/sandbox/issues/all?repo=` | One issue per scenario |
| `POST` | `/api/sandbox/pulls/{n}/request-changes` | Play the reviewer |

See [Sandbox API](Sandbox-API).

## Web UI — not contracts

| Route | Page |
|---|---|
| `/` | Product overview, repository cards, register CTA |
| `/pipeline?repo=` | The board |
| `/tasks/{id}` | Task detail |
| `/repositories/new` | Step-by-step registration; `POST /repositories` (form-encoded) registers |
| `/learnings` | What reviewers taught menD |
| `/deck` | Presentation deck, reading live numbers |
| `/fragments/live?repo=` | htmx polling fragment |

## Operations

| Route | Purpose |
|---|---|
| `/actuator/health` | Liveness (`{"status":"UP"}`) |
| `/actuator/info` | Build info |
| `/actuator/metrics` | Micrometer metrics |
| `/actuator/prometheus` | Scrape endpoint |

## Conventions

- Timestamps are ISO-8601 UTC (`2026-08-30T07:22:32.024740Z`).
- Repositories are `owner/name`; tasks are keyed `owner/name#issueNumber` and addressed by numeric
  `id`.
- Write routes return `202` when the work is queued and `200` when a verdict was recorded
  synchronously.
- `404` bodies are empty. Only `POST /api/repositories` returns `{"error": …}`.
- No pagination, no filter parameters on the JSON API today. `GET /api/tasks` returns everything.
