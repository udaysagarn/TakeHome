# Sandbox API

The sandbox runs the entire menD workflow with no GitHub App, no Devin API key, no network access
and no ACU spend. GitHub and Devin are replaced by in-memory simulations; **everything else is the
production code** — the same state machine, the same gate, the same verifier, the same review loop.

It exists under the `sandbox` Spring profile only, so a deployment talking to the real GitHub cannot
reach these routes.

## Start it

```bash
./deploy/simulate.sh            # Docker, files one issue per scenario, prints what to watch
# or
SPRING_PROFILES_ACTIVE=sandbox mvn -B spring-boot:run
```

## Scenarios

| Scenario | Simulates | Path |
|---|---|---|
| `CLEAN_FIX` | A well-formed issue Devin fixes and the repository's CI proves | `DISCOVERED → … → SUCCEEDED` |
| `NOT_A_CANDIDATE` | An issue needing a human decision | `DISCOVERED → NOT_A_CANDIDATE`, no remediation session created |
| `UNVERIFIED` | A repository with no CI and no verifier | `… → VERIFYING → UNVERIFIED` |
| `REVIEW_THEN_FIX` | A reviewer rejects once, the fix lands and teaches menD | `… → CHANGES_REQUESTED → RUNNING → SUCCEEDED`, writes lessons |

## Routes

### `GET /api/sandbox`

The whole surface plus current state — every label, comment, pull request and review menD wrote to
the fake GitHub.

```json
{
  "repository": "udaysagarn/superset",
  "scenarios": [{"scenario": "CLEAN_FIX", "simulates": "a well-formed issue that Devin fixes and the repository's CI proves"}],
  "state": {"issues": [{"issue": "udaysagarn/superset#101", "scenario": "CLEAN_FIX",
                        "labels": ["menD:done"], "comments": ["### Accepted for autonomous remediation…"]}]}
}
```

### `POST /api/sandbox/issues?scenario=CLEAN_FIX&repo=owner/name`

Files one simulated issue. `scenario` defaults to `CLEAN_FIX`, `repo` to the configured repository.
Returns the issue as GitHub would represent it.

### `POST /api/sandbox/issues/all?repo=owner/name`

One issue per scenario — the fastest way to fill the board for a demo. Returns the array of issues.
The set settles in about a minute.

### `POST /api/sandbox/pulls/{pullNumber}/request-changes`

Play the human reviewer.

```bash
curl -X POST localhost:8080/api/sandbox/pulls/9001/request-changes \
     -H 'content-type: application/json' \
     -d '{"reviewer":"you","body":"add a test next to the component"}'
```

Body is optional (`{"reviewer": …, "body": …}`); both fields default. `200` with the simulated
review, or `404 {"error":"no simulated pull request #9001"}`.

What follows is the real review loop: `CHANGES_REQUESTED`, the comments handed back to the session
that wrote the code, and — if the feedback is generalisable — a lesson on `/learnings` with the
review quoted as its evidence.

## What to look at

| | |
|---|---|
| `/pipeline` | The board, including the honest `UNVERIFIED` column |
| `/learnings` | What the simulated reviewer taught menD |
| `/api/sandbox` | Everything menD wrote to the fake GitHub |

Stop with `docker compose down -v` (`-v` also throws away the simulated state).
