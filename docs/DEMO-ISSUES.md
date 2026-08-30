# The demo issues

The live demo runs against [`udaysagarn/superset`](https://github.com/udaysagarn/superset), a fork of
`apache/superset`. Every issue below was derived from the fork's actual tree — a lockfile pin, a compiled
requirements pin, a file that carries its own lint suppressions — or mirrored from a still-open upstream
report. None of them are invented, and each one states the command that proves it.

menD only starts work on an issue once it carries the trigger label (`menD:fix` by default), so the board stays
empty — and no ACU is spent — until you label one in front of the audience.

| Issue | What it is | Why it was chosen | Expected outcome |
| --- | --- | --- | --- |
| [#2](https://github.com/udaysagarn/superset/issues/2) | `brace-expansion@5.0.8` under `nx` in `superset-frontend/package-lock.json` (GHSA-rgw5-rvv9-x895, HIGH) | Lockfile-only bump with a published patch release; upstream landed the same bump for its sibling workspaces in #43664 / #43665 | `SUCCEEDED` — the fix is objectively provable by re-querying OSV for the pinned version |
| [#3](https://github.com/udaysagarn/superset/issues/3) | `brace-expansion@5.0.7` in `superset-websocket/package-lock.json` (GHSA-mh99-v99m-4gvg + GHSA-rgw5-rvv9-x895) | Same class of fix in a second workspace — shows the repository profile picking the right lockfile and commands | `SUCCEEDED` |
| [#4](https://github.com/udaysagarn/superset/issues/4) | Two `pacote` copies below `21.5.1` in `superset-frontend/package-lock.json` (GHSA-w4pp-8pjf-rmxw, HIGH) | A transitive dependency resolved twice, so the fix has to touch both entries and leave `package.json` alone | `SUCCEEDED` |
| [#5](https://github.com/udaysagarn/superset/issues/5) | Eight `@typescript-eslint/no-explicit-any` suppressions in `standardizedFormData.ts` | Not a dependency bump: a bounded code change in a 297-line file with a co-located test suite, so it exercises the "edit the target repository's tests" path | `SUCCEEDED`, with a test change in the PR |
| [#7](https://github.com/udaysagarn/superset/issues/7) | `jaraco-context==6.0.1` in `requirements/development.txt` (GHSA-58pv-8j8x-9vj2, HIGH path traversal) | Python side of the same discipline; one pin line, precedent in the repo's own `chore(deps)` history | `SUCCEEDED` |
| [#8](https://github.com/udaysagarn/superset/issues/8) | `python-multipart==0.0.29` in `requirements/development.txt` (four advisories, top severity HIGH) | Same shape as #7 but with several advisories cleared by one bump | `SUCCEEDED` |
| [#9](https://github.com/udaysagarn/superset/issues/9) | X-Axis Label Interval `All` skips labels, mirroring the open upstream report [apache/superset#36325](https://github.com/apache/superset/issues/36325) | The counter-example: a real bug with no reproduction, no objective pass condition, and a product judgement in the middle of it | `NOT_A_CANDIDATE` — the gate declines it and says why, without spending remediation ACU |

## How each one was vetted

The dependency issues came from querying [OSV](https://osv.dev) with the versions actually pinned in the fork:

```bash
# npm workspaces
jq -r '.packages | to_entries[] | select(.key|contains("node_modules/")) | "\(.value.version) \(.key)"' \
  superset-frontend/package-lock.json

curl -s https://api.osv.dev/v1/query \
  -d '{"package":{"name":"brace-expansion","ecosystem":"npm"},"version":"5.0.8"}' | jq -r '.vulns[].id'

# python requirements
grep -A1 '^jaraco-context==' requirements/development.txt
curl -s https://api.osv.dev/v1/query \
  -d '{"package":{"name":"jaraco-context","ecosystem":"PyPI"},"version":"6.0.1"}' | jq -r '.vulns[].id'
```

Advisories with no released fix were deliberately left out — `xlsx`, `image-size` and `extract-zip` are all
flagged against this tree but have no patched version to move to, so they are not bounded work and menD would
be right to refuse them.

## Running the demo

1. Register the fork (or use the pre-registered entry in the Docker image) and confirm `accessState: VALIDATED`
   on `/api/repositories`.
2. Label one dependency issue `menD:fix`. One is enough for the happy path: the board at `/flows` shows it
   move `DISCOVERED → CRITERIA_PENDING → READY → DISPATCHED → RUNNING → PR_OPEN → VERIFYING → SUCCEEDED`.
3. Label #9 as well. It stops at `NOT_A_CANDIDATE` with the gate's reason on the task page — that contrast is
   the point of the demo, not an accident of the data.
