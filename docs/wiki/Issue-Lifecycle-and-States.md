# Issue lifecycle and states

The state machine is the product. It is enforced in one place: an illegal transition throws rather
than silently corrupting the pipeline, and every legal one writes an event row.

## States

| State | Meaning | Terminal |
|---|---|---|
| `DISCOVERED` | Trigger label observed. Nothing spent yet | |
| `CRITERIA_PENDING` | Deterministic pre-filters passed; a scoping session is establishing criteria | |
| `READY` | Verifiable criteria exist; queued for remediation | |
| `DISPATCHED` | A remediation Devin session has been created | |
| `RUNNING` | The remediation session is working | |
| `BLOCKED` | The session reported `blocked`; menD nudges it | |
| `PR_OPEN` | The session produced a pull request | |
| `VERIFYING` | Independent evidence is being gathered | |
| `CHANGES_REQUESTED` | A human reviewer asked for changes; the comments outrank the contract | |
| `SUCCEEDED` | Pull request open, criteria asserted, **and** an independent verifier agreed | ✔ |
| `UNVERIFIED` | A fix exists, but nothing independent could prove it. Not a success | ✔ |
| `FAILED` | No pull request, or verification red after the attempt budget | ✔ * |
| `NOT_A_CANDIDATE` | No machine-checkable definition of done could be established | ✔ |
| `NEEDS_HUMAN` | Escalated after exhausting nudges or attempts | ✔ |
| `CANCELLED` | Cancelled from the dashboard, or the issue became inaccessible | ✔ |

\* `FAILED` stops the reconciler but is still re-dispatchable while the attempt budget allows.

## Legal transitions

```
DISCOVERED         → CRITERIA_PENDING | READY | NOT_A_CANDIDATE
CRITERIA_PENDING   → READY | NOT_A_CANDIDATE | NEEDS_HUMAN
READY              → DISPATCHED | NEEDS_HUMAN
DISPATCHED         → RUNNING | BLOCKED | PR_OPEN | FAILED
RUNNING            → BLOCKED | PR_OPEN | FAILED
BLOCKED            → RUNNING | PR_OPEN | NEEDS_HUMAN | FAILED
PR_OPEN            → VERIFYING | SUCCEEDED | UNVERIFIED | CHANGES_REQUESTED | NEEDS_HUMAN | FAILED
VERIFYING          → SUCCEEDED | UNVERIFIED | CHANGES_REQUESTED | NEEDS_HUMAN | FAILED | RUNNING
CHANGES_REQUESTED  → RUNNING | PR_OPEN | NEEDS_HUMAN | FAILED
FAILED             → DISPATCHED | NEEDS_HUMAN
UNVERIFIED         → VERIFYING | SUCCEEDED | CHANGES_REQUESTED
any active state   → CANCELLED
```

`SUCCEEDED`, `NOT_A_CANDIDATE`, `NEEDS_HUMAN` and `CANCELLED` accept nothing. `UNVERIFIED` can still
be upgraded when evidence arrives later — that is the point of having it.

## Buckets

`bucket` on a task row collapses the states for reporting:

| Bucket | States |
|---|---|
| `in_flight` | Everything not terminal |
| `succeeded` | `SUCCEEDED` |
| `unverified` | `UNVERIFIED` |
| `failed` | `FAILED`, `NEEDS_HUMAN` |
| `excluded` | `NOT_A_CANDIDATE`, `CANCELLED` |

`unverified` is deliberately its own bucket. It is never folded into `succeeded`.

## Labels

The database is the source of truth; labels are a **projection** of it onto the issue, so a human
looking at GitHub sees the same story without opening the dashboard.

| Label (default) | Applied when |
|---|---|
| `menD:fix` | You apply it — the trigger |
| `menD:in-progress` | A remediation session is dispatched |
| `menD:pr-open` | The pull request exists |
| `menD:done` | Verified success |
| `menD:unverified` | A fix with no independent evidence |
| `menD:changes-requested` | A reviewer asked for changes |
| `menD:needs-human` | Escalated |
| `menD:not-a-candidate` | Excluded by the gate |

Every label swap is accompanied by an issue comment explaining the move — including, for
`NOT_A_CANDIDATE`, exactly which gate check failed and what to add to make the issue automatable.

Names are configurable (`mend.github.*-label`), and the trigger label can be overridden per
repository.

## Comments menD writes

| Where | When | Contains |
|---|---|---|
| Issue | Criteria established | The contract, the files in scope, the risk, the scoping session link |
| Issue | Not a candidate | The failed gate checks and how to fix them |
| Issue | Dispatched | The Devin session link and the attempt number |
| Issue | Pull request opened | The pull request link |
| Issue | Remediated / unverified | Per-criterion evidence, test evidence, time from label to pull request |
| Pull request | Verification finished | The verdict, **what produced it**, and the command table with exit codes |
