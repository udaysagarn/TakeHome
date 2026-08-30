# Errors and limits

## Status codes

| Code | Where | Meaning |
|---|---|---|
| `200` | Reads, `POST /api/repositories`, `POST /api/repositories/{id}/validate`, `POST /api/tasks/{id}/cancel` | Verdict recorded. On registration this does **not** mean access is good — read `accessState` |
| `202` | `POST /api/issues/{n}/ingest`, most webhook deliveries | Durably queued. Never "done" |
| `400` | `POST /api/repositories` | `{"error":"expected owner/name, got: notaslug"}` |
| `401` | `POST /webhooks/github` | `invalid signature` |
| `404` | `GET /api/tasks/{id}`, `POST /api/tasks/{id}/cancel`, `POST /api/repositories/{id}/validate`, `POST /api/issues/{n}/ingest` | Unknown id, or GitHub has no such issue. Body is empty |
| `500` | Anywhere | Logged with a stack trace; the webhook returns the literal body `error` |

Only `POST /api/repositories` returns a structured `{"error": …}` body. Everything else signals
failure with the status code alone, so check the status, not the body.

## Failure modes that are not HTTP errors

menD is asynchronous, so most "failures" arrive as state, not as a status code.

| Symptom | Where it shows | What it means |
|---|---|---|
| `accessState: NO_ACCESS` / `MISSING_PERMISSION` | Repository record | Fix `accessError`, then re-validate |
| `indexState: INDEX_FAILED` | Repository record | Profiling session failed; `indexError` says why. Remediation still works, with weaker context |
| `NOT_A_CANDIDATE` | Task state | The gate refused it. `exclusionReason` and the issue comment list every failed check |
| `UNVERIFIED` | Task state | A fix exists, nothing independent could prove it. See [Verification](Verification-and-Evidence) |
| `FAILED` | Task state | No pull request, or verification red. `lastError` has the detail; re-dispatchable while the attempt budget allows |
| `NEEDS_HUMAN` | Task state | Nudges or attempts exhausted, or too many review rounds. Terminal |
| `lease.overdue: true` | Task detail | The owner missed its predicted completion. A signal, not an outcome |
| `lease.takeovers > 0` | Task detail | The task was reclaimed from a dead worker. Persistent non-zero means workers are dying |

## Limits

| Limit | Default | Effect when hit |
|---|---|---|
| `mend.engine.max-concurrent-sessions` | 4 | New work waits in `READY` |
| `mend.engine.max-attempts` | 2 | `NEEDS_HUMAN` |
| `mend.engine.max-nudges` | 3 | `NEEDS_HUMAN` |
| `mend.engine.session-timeout` | 3h | The session is abandoned and the attempt fails |
| `mend.learning.max-review-rounds` | 3 | `NEEDS_HUMAN` |
| `mend.devin.criteria-acu-limit` / `remediation-acu-limit` | 3 / 10 | Devin stops at the cap |
| `mend.verify.tier-timeout` | 45m | The tier is abandoned; the next one is tried, or `UNVERIFIED` |

menD does not impose HTTP rate limits of its own. GitHub's apply as usual; a repository whose
installation is throttled surfaces as failed calls in the logs and stalled tasks on the board.

## Authentication and exposure

**menD does not authenticate its own HTTP API.** There is no API key, no session, no per-route
authorisation. The only authenticated surface is the webhook, via HMAC signature.

That is fine for a laptop demo, a single-tenant deployment behind your VPN, or a container with no
public ingress. It is **not** fine on the open internet: anyone who can reach `/api` can register
repositories, trigger remediation and cancel tasks. Put it behind your identity proxy, and set
`GITHUB_WEBHOOK_SECRET` so the one route GitHub calls is verified.

Related: `/actuator` exposes only `health`, `info`, `metrics` and `prometheus`, and health details
are shown only when authorised.

## Concurrency guarantees

- One task per `(repo, issueNumber)`; duplicate triggers converge on it.
- One owner per task, taken by a conditional update rather than a lock, with optimistic locking
  underneath.
- Workers are stateless: a worker that dies loses its lease and another resumes from the persisted
  row. Nothing is held in memory that a restart would lose.
- Every state change is validated against the state machine and appended to the event log, so an
  illegal transition fails loudly instead of corrupting the flow.
