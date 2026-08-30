# Reports and metrics

## KPI block

```bash
curl -s localhost:8080/api/summary
```

```json
{"total":8,"inFlight":0,"prsOpened":6,"succeeded":4,"unverified":2,"excluded":2,"escalated":0,
 "successRatePct":66.66666666666667,"medianMinutesToPr":0,"acuBudgeted":60,"acuPerSuccess":15.0,
 "exclusionRatePct":33.333333333333336,"engineerHoursAvoided":10.0}
```

| Field | Meaning |
|---|---|
| `total` | Issues ingested |
| `inFlight` | Non-terminal tasks |
| `prsOpened` | Tasks that produced a pull request |
| `succeeded` | Independently verified fixes — **only** these count as success |
| `unverified` | Fix opened, no independent evidence. Never folded into `succeeded` |
| `excluded` | Turned away by the criteria gate, plus cancellations |
| `escalated` | `NEEDS_HUMAN` |
| `successRatePct` | `succeeded` over resolved work; `null` before anything resolves |
| `medianMinutesToPr` | Label to pull request, median; `null` with no data |
| `acuBudgeted` | ACU ceiling committed across sessions, not ACU consumed |
| `acuPerSuccess` | `acuBudgeted / succeeded`; `null` before the first success |
| `exclusionRatePct` | How much of the backlog the gate turns away — the honesty metric |
| `engineerHoursAvoided` | An estimate, and labelled as one wherever it is shown |

`acuBudgeted` is a *ceiling*, not spend. For real consumption, read your Devin usage page.

## Markdown digest

```bash
curl -s localhost:8080/api/report
```

`GET /api/report` → `text/markdown;charset=UTF-8`: the outcomes table above, the remediated issues
with their pull requests and time-to-PR, the unverified ones with why nothing could prove them, and
the exclusions with the gate's reasoning. Written to be pasted into a review or a Slack channel
unedited.

## Per-state counts

`GET /api/states` → every state with its count, including zeros. See
[Polling task status](Polling-Task-Status#counts).

## Per-repository numbers

The same KPI block is computed per repository and returned inside each card on the overview page.
Over the API, filter `GET /api/tasks` by `repo` and count client-side.

## Operational metrics

Spring Boot Actuator, exposing `health`, `info`, `metrics` and `prometheus`:

```bash
curl -s localhost:8080/actuator/health      # {"status":"UP"}
curl -s localhost:8080/actuator/prometheus  # scrape target
```

All metrics are tagged `application=mend-orchestrator`.

## Human views

| Route | What |
|---|---|
| `/` | Product overview, repository cards, register CTA |
| `/flows?repo=owner/name` | The board, refreshing itself |
| `/tasks/{id}` | Everything menD persisted about one issue |
| `/repositories/new` | Step-by-step registration |
| `/learnings` | What reviewers have taught menD |
| `/deck` | The presentation deck, reading live numbers from this instance |

These are UI routes. They are not contracts and may change shape without notice.
