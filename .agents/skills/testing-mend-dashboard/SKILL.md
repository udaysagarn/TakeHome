---
name: testing-mend-dashboard
description: How to run and test the menD (D1) Spring Boot control-plane dashboard end-to-end without spending ACUs or triggering real Devin remediation sessions.
---

# Testing the menD control-plane dashboard

## Running the app

JDK 21 + Maven. From the repo root:

```
MEND_REPO=<owner>/<repo> \
GITHUB_APP_ID=<id> \
GITHUB_APP_INSTALLATION_ID=<id> \
GITHUB_APP_PRIVATE_KEY="$(cat /path/to/key.pem)" \
DEVIN_ORG_ID=<org-id> \
DEVIN_API_KEY=$DEVIN_API_KEY \
mvn spring-boot:run
```

Serves on http://localhost:8080. State lives in an H2 file under `data/` — only one
process may hold it at a time (file lock).

## Safety: this engine spends real money

A running instance polls GitHub every 30s and will create real Devin sessions for any open
issue carrying the trigger label (default `menD:fix`). When testing:

- Never add the trigger label to an issue.
- Never call `POST /api/issues/{n}/ingest` or `POST /api/tasks/{id}/cancel`.
- For a no-spend copy, stop the live instance first, then start with
  `MEND_ENGINE_ENABLED=false MEND_POLLING_ENABLED=false` on another port against the same H2 file.

## Where things are

- `src/main/resources/templates/dashboard.html` — shell, header buttons (Report / Metrics / Theme)
  and the inline theme JS (localStorage key `mend-theme`).
- `src/main/resources/templates/fragments/live.html` — everything below the header: KPI cards,
  pipeline board, issues table, exclusion table, state-transition stream.
- `src/main/resources/static/css/devin.css` — theme tokens: `:root`/`.light`, `.dark`,
  and a `.high-contrast` block that (as of this writing) nothing ever applies.
- `src/main/java/ai/devin/mend/web/DashboardService.java` — the `BOARD` map defines the six board
  columns and the `Kpis` record defines every number the UI and `/api/report` show.

## Read-only verification surface

Everything can be verified without mutating state:

```
curl -s localhost:8080/api/summary        # KPI ground truth, compare against the cards
curl -s localhost:8080/api/tasks          # per-issue rows incl. state/session/PR/note
curl -s localhost:8080/api/tasks/{id}/events   # full state history for one task
curl -s localhost:8080/api/report         # markdown leadership report
curl -s localhost:8080/actuator/prometheus | grep '^mend_'
```

## Proving auto-refresh actually polls

The dashboard uses htmx `hx-get="/fragments/live" hx-trigger="every 5s"`. Since the data is
static during a read-only test, a screenshot cannot distinguish "refreshing" from "frozen".
Instead sample the request counter twice, ~12s apart:

```
curl -s localhost:8080/actuator/prometheus | grep 'http_server_requests_seconds_count.*fragments'
```

The count should rise by ~2-3. Pair that with a screenshot showing the page scroll position
unchanged, which proves it is a fragment swap rather than a full page reload.

## Known gaps to expect (verify before reporting as new)

- The Theme button only alternates light/dark; `.high-contrast` is defined in CSS but is not
  reachable from the UI, and it only overrides two light-mode tokens so it would look wrong
  under `.dark` anyway.
- There is no per-task detail view — board cards and table rows are not clickable, and the
  success-criteria contract (acceptance criteria / verification commands) is stored on the
  entity but never surfaced in the UI or in `/api/tasks`.
- "Engineer-hours avoided" appears only in `/api/report`, not as a dashboard KPI card.
- The Note column shows raw internal error strings (e.g. Java NPE messages) when a task
  carries a `lastError`.

## Devin secrets needed

- `DEVIN_API_KEY`
- `GITHUB_APP_INSTALLATION_ID`
- `GITHUB_APP_PRIVATE_KEY`
