# Verification and evidence

menD will not call a fix a success on the word of the session that wrote it. `SUCCEEDED` requires
evidence from something else; when no such evidence exists, the task lands in `UNVERIFIED` and is
counted separately.

## The tiers, strongest first

| Tier | What it is | Trust |
|---|---|---|
| `REPO_CI` | The repository's own checks on the pull request head | Authoritative, and costs menD nothing |
| `CONTRACT_WORKFLOW` | `mend-verify.yml`, merged into the repository by a human, running *this task's* `verification_commands` inside the repository's own CI | Strong: right toolchain, auditable, lives in your repo |
| `VERIFIER_SESSION` | A separate Devin session at the pull request head that only runs commands and reports exit codes — it never writes code | Weaker: still Devin, but not the Devin that wrote the change |
| `NONE` | Nothing independent was available | Not evidence — produces `UNVERIFIED` |

The verifier stops at the first tier that can answer. Repository checks are matched first; check
runs whose name starts with `mend.verify.contract-check-prefix` (default `menD`) count as the
contract tier, everything else as repository CI. A commit status is accepted as repository CI when
there are no check runs at all.

**What menD deliberately does not do:** run arbitrary repository test suites inside its own
container. One image cannot hold every toolchain, and executing untrusted repository code would be a
worse problem than an unverified pull request.

## Verdicts

`PASSED` · `FAILED` · `PENDING` · `UNAVAILABLE`

`PENDING` means a tier was reached but has not reported yet (the contract workflow is dispatched at
most once per task; `mend.verify.tier-timeout`, default 45 minutes, bounds the wait).
`UNAVAILABLE` means no tier could answer at all.

## The evidence object

On `GET /api/tasks/{id}`:

```json
"verification": {
  "tier": "REPO_CI",
  "verdict": "PASSED",
  "summary": "ci / build-and-test passed on the pull request head",
  "commands": [],
  "check_url": "https://github.com/udaysagarn/superset/pull/9006/checks",
  "independent": true
}
```

`commands` carries `{"command", "exit_code", "output"}` per command when the tier ran them (contract
workflow and verifier session). `independent` is false exactly when the tier is `NONE` or the
verdict is `UNAVAILABLE`.

The same evidence is posted as a comment **on the pull request**, naming its provenance in plain
words — "the repository's own required checks", "the menD verification contract workflow in the
repository", "a separate Devin session that only ran the commands", or "nothing independent of the
session that wrote the code" — followed by the command table. A reviewer never has to take menD's
word for it.

## Installing the contract workflow

Copy [`deploy/target-repo/mend-verify.yml`](https://github.com/udaysagarn/menD/blob/main/deploy/target-repo/mend-verify.yml)
into `.github/workflows/` in the target repository and merge it like any other change. It takes a
`pull_request` number and a newline-separated `commands` input, checks out the pull request head,
runs each command in the repository's own environment, and reports one check run per command.

Two consequences worth stating out loud: the verification harness is auditable in *your* repository
rather than hidden in menD, and menD scales to any toolchain without knowing anything about it.

## What `UNVERIFIED` means for you

- A pull request exists and the criteria were asserted by the session that wrote it.
- Nothing independent confirmed it.
- It is **not** counted in `succeeded`, the success rate, or the "remediated" report section — it
  has its own KPI and its own board column and its own `menD:unverified` label.
- It is terminal in current processing: `UNVERIFIED → VERIFYING → SUCCEEDED` is a legal transition,
  but nothing re-drives an unverified task, so late evidence (a CI run that eventually appears, a
  merged contract workflow) does not automatically upgrade it.

Turning off the fallbacks is a supported choice: `mend.verify.verifier-session-enabled=false` leaves
only real CI, so everything else is honestly `UNVERIFIED`.
