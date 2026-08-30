# Triggering issue resolution

There are three ways to put an issue into menD's queue. All three converge on the same code path and
the same durable task, so a duplicate trigger never produces duplicate work.

## 1. Label the issue (the normal way)

Apply the trigger label — `menD:fix` by default, per-repository overridable — to a GitHub issue.

- With a [webhook](Webhooks) configured, menD picks it up in seconds.
- Without one, the poller finds it within `MEND_POLL_INTERVAL` (default 30s).

The label *is* the contract for humans: nothing happens to an unlabelled issue. To stop work on one
already in flight, cancel it (below) — removing the label alone does not.

## 2. `POST /api/issues/{number}/ingest`

Manual trigger, for demos and for re-driving an issue without touching labels.

```bash
curl -s -X POST 'localhost:8080/api/issues/108/ingest?repo=udaysagarn/superset'
```

| | |
|---|---|
| Path | `number` — the GitHub issue number |
| Query | `repo` — optional `owner/name`; defaults to the primary registered repository |
| `202 Accepted` | `{"task":"udaysagarn/superset#108"}` |
| `404 Not Found` | Empty body — GitHub has no such issue, or menD cannot see it |

`202` means the task row exists and the reconciler owns it from here. It does **not** mean a Devin
session has started.

## What happens next

```
DISCOVERED → CRITERIA_PENDING → READY → DISPATCHED → RUNNING → PR_OPEN → VERIFYING → SUCCEEDED
                     ↓                                                        ↓
              NOT_A_CANDIDATE                                            UNVERIFIED
```

1. **Pre-filter (free, deterministic).** Body shorter than `mend.triage.min-body-length` (60 chars),
   missing title, a denylisted label (`question`, `discussion`, `epic`, `wontfix`, `invalid`), or an
   unfilled template → `NOT_A_CANDIDATE` immediately, without spending anything.
2. **Scoping session.** A Devin session (ACU-capped, `MEND_CRITERIA_ACU`, default 3) produces the
   [success criteria contract](Success-Criteria-Contract) as structured output. Confidence below
   `MEND_MIN_CONFIDENCE` (0.7), no acceptance criteria, no verification commands, or any blocking
   unknown → `NOT_A_CANDIDATE`, with the reason written back as an issue comment.
3. **Remediation session.** ACU-capped (`MEND_REMEDIATION_ACU`, default 10), given the contract, the
   repository profile and the active [learnings](Learnings-API). It must open a pull request and
   must not weaken existing tests.
4. **Verification.** Independent evidence or `UNVERIFIED` — see
   [Verification and evidence](Verification-and-Evidence).

## Skipping the scoping session

If you already know the definition of done, put it in the issue body and menD uses it verbatim
instead of paying for a scoping session:

````markdown
```devin-criteria
{
  "is_candidate": true,
  "confidence": 0.9,
  "problem_restatement": "…",
  "acceptance_criteria": ["…"],
  "verification_commands": ["npm test -- --run"],
  "files_in_scope": ["superset-frontend/src/…"],
  "test_plan": "…",
  "risk": "low",
  "blocking_unknowns": [],
  "rationale": "…"
}
```
````

A block that fails to parse is ignored and menD falls back to a scoping session.

## Idempotency and duplicates

- A task is unique by `(repo, issue_number)`. Re-labelling, a redelivered webhook and a manual
  `ingest` on the same issue all resolve to the same row.
- Exactly one worker owns a task at a time, via a lease with an owner, an expiry and a predicted
  completion time. A worker that dies has its lease expire, and a different stateless worker
  resumes from the persisted row. See [Polling task status](Polling-Task-Status#lease).
- Re-triggering a terminal task re-enters the flow only where the state machine allows it
  (for example `FAILED → DISPATCHED` while the attempt budget lasts); `SUCCEEDED`,
  `NOT_A_CANDIDATE`, `NEEDS_HUMAN` and `CANCELLED` are final.

## Stopping work

```bash
curl -s -X POST localhost:8080/api/tasks/8/cancel
```

`POST /api/tasks/{id}/cancel` → `200 {"state":"CANCELLED"}`, or `404` when the id is unknown.
Cancellation is only legal from an active state; a terminal task rejects it.

## Budgets

| Control | Default | Effect |
|---|---|---|
| `MEND_CRITERIA_ACU` | 3 | Hard cap on a scoping session |
| `MEND_REMEDIATION_ACU` | 10 | Hard cap on a remediation session |
| `MEND_MAX_ATTEMPTS` | 2 | Remediation attempts before `NEEDS_HUMAN` |
| `MEND_MAX_CONCURRENT` | 4 | Devin sessions in flight across all repositories |
| `MEND_MIN_CONFIDENCE` | 0.7 | Below this, the issue is excluded rather than attempted |
