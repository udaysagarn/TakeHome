# Polling task status

Every issue menD has seen is a durable **task**, identified by its numeric `id` and keyed uniquely by
`(repo, issueNumber)`. Nothing is held in memory; a restart loses nothing.

## Every task

```bash
curl -s localhost:8080/api/tasks
```

`GET /api/tasks` → array of task rows, most recently updated first.

```json
{
  "id": 8,
  "repo": "udaysagarn/superset",
  "issueNumber": 108,
  "title": "Replace explicit any in the chart controls type definitions",
  "issueUrl": "https://github.com/udaysagarn/superset/issues/108",
  "state": "SUCCEEDED",
  "bucket": "succeeded",
  "sessionUrl": "https://app.devin.ai/sessions/sandbox-remediation-17",
  "prUrl": "https://github.com/udaysagarn/superset/pull/9006",
  "ciStatus": "PASSED",
  "confidence": 0.86,
  "attempts": 1,
  "acu": 10,
  "note": "ci / build-and-test passed on the pull request head",
  "minutesToPr": 0,
  "ageMinutes": 0,
  "updatedAt": "2026-08-30T07:22:32.024740Z",
  "ownerId": null,
  "etaAt": null,
  "etaLabel": null,
  "overdue": false
}
```

`bucket` is the coarse grouping used by the board: `in_flight`, `succeeded`, `unverified`, `failed`,
`excluded`. Poll this if all you need is "how is it going".

There is no server-side filter parameter on this route; filter client-side on `repo` or `state`.
(The HTML board at `/pipeline?repo=owner/name` does filter, but that is a UI route, not a contract.)

## One task, in full

```bash
curl -s localhost:8080/api/tasks/8
```

`GET /api/tasks/{id}` → `200` with the detail object, or `404` with an empty body.

The detail contains everything menD persisted about the issue:

| Field | What it tells you |
|---|---|
| `task` | The same row shape as above |
| `criteria`, `criteriaJson`, `criteriaHash` | The [contract](Success-Criteria-Contract) Devin was held to, and the hash that proves it did not change mid-flight |
| `criteriaSessionUrl`, `remediationSessionUrl`, `remediationSessionId`, `verifierSessionUrl` | Every Devin session involved, linkable |
| `verification`, `verificationTier` | The [evidence](Verification-and-Evidence), including per-command exit codes |
| `lease` | Who owns the task right now, and what they promised |
| `createdAt`, `readyAt`, `dispatchedAt`, `prOpenedAt`, `completedAt` | Phase timestamps |
| `attempts`, `nudges`, `lastError`, `exclusionReason` | Why it is where it is |
| `reviewRounds`, `reviewerFeedback` | What human reviewers said, and how many rounds |
| `timeline` | The full append-only state history |

## Timeline only

```bash
curl -s localhost:8080/api/tasks/8/events
```

`GET /api/tasks/{id}/events` → append-only transitions, oldest first. Unknown ids return `[]`.

```json
{
  "id": 34,
  "taskId": 8,
  "taskKey": "udaysagarn/superset#108",
  "fromState": "CRITERIA_PENDING",
  "toState": "READY",
  "reason": "criteria established by scoping session",
  "actor": "orchestrator",
  "occurredAt": "2026-08-30T07:21:47.107709Z"
}
```

Every state change in menD goes through one transition method that validates it against the state
machine, stamps timestamps and writes one of these rows — so this list is the audit trail, not a
best-effort log.

## Counts

```bash
curl -s localhost:8080/api/states
curl -s localhost:8080/api/summary
```

`GET /api/states` → every state with its count, including zeros:

```json
{"DISCOVERED":0,"CRITERIA_PENDING":0,"READY":0,"DISPATCHED":0,"RUNNING":0,"BLOCKED":0,
 "PR_OPEN":0,"VERIFYING":0,"CHANGES_REQUESTED":0,"SUCCEEDED":4,"UNVERIFIED":2,"FAILED":0,
 "NOT_A_CANDIDATE":2,"NEEDS_HUMAN":0,"CANCELLED":0}
```

`GET /api/summary` → the KPI block, described in [Reports and metrics](Reports-and-Metrics).

## Lease

The `lease` block on the task detail is how you tell a working task from a stuck one:

```json
{
  "status": "released",
  "ownerId": null,
  "acquiredAt": "2026-08-30T07:21:37.053686Z",
  "expiresAt": null,
  "secondsRemaining": null,
  "etaAt": null,
  "overdue": false,
  "takeovers": 0
}
```

- `ownerId` — the worker that currently holds the task. Exactly one, enforced by a conditional
  update, not by a lock.
- `expiresAt` / `secondsRemaining` — the heartbeat deadline. A worker renews while it makes
  progress; if it dies, the lease expires and another worker reclaims the task from the persisted
  row.
- `etaAt` / `overdue` — when the owner predicted it would finish, and whether that has passed.
  Overdue is a signal, not an outcome: menD escalates on attempt and nudge budgets, not on the clock.
- `takeovers` — how many times the task has been reclaimed from a dead owner. Persistently non-zero
  means workers are dying.

## Polling advice

- Poll `GET /api/tasks` on an interval (the UI uses ~5s) and drill into `GET /api/tasks/{id}` only
  when a state changes.
- Terminal states — `SUCCEEDED`, `UNVERIFIED`, `FAILED`, `NOT_A_CANDIDATE`, `NEEDS_HUMAN`,
  `CANCELLED` — will not change again on their own, so stop polling them.
- If you want push instead of poll, the GitHub labels mirror the state onto the issue itself; see
  [Issue lifecycle and states](Issue-Lifecycle-and-States#labels).
