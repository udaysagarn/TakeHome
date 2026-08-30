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

## Fastest safe way to test (no credentials, no ACU spend)

From the menD repo root:

```
MEND_ENGINE_ENABLED=false MEND_POLLING_ENABLED=false \
  java -jar target/mend-orchestrator-0.1.0.jar    # rebuild with `mvn -B -DskipTests package` if stale
```

With no GitHub key present the app logs "GitHub token not configured: ingestion and issue
feedback are disabled" and cannot make outbound calls — the safest possible test posture.
Use `setsid nohup ... &` to start it; a `pkill -f mend-orchestrator && java -jar ...` chain in
one shell command can kill the shell before the restart runs.

## Seeding demo data into H2

A fresh jar run starts with an empty DB, so pages are empty. Seed with the H2 console-less
client (`java -cp ~/.m2/.../h2-*.jar org.h2.tools.Shell -url jdbc:h2:file:./data/mend ...`)
**while the app is stopped** — H2 file mode allows a single writer. Gotchas seen:
- The schema is owned by the Flyway migrations in `src/main/resources/db/migration/{vendor}`,
  and Hibernate runs with `ddl-auto: validate`. The enum-backed columns
  (`remediation_task.state`, `task_event.from_state`, `task_event.to_state`) are varchar(32)
  with no CHECK constraint, so every state including `UNVERIFIED` and `CHANGES_REQUESTED`
  inserts cleanly. Any schema change needs a new versioned migration — editing an entity alone
  now fails startup instead of silently drifting.
- Keep seeded timestamps consistent with the box clock (it may be set far in the future),
  otherwise ages, ETAs and "overdue" markers look nonsensical.
- Registering a nonexistent repo through `/repositories/new` is a safe way to exercise
  validation: with no credentials it persists with `NO_ACCESS` and a readable message.

## Routes (current product revision)

`/` product overview (architecture SVG + repo cards), `/pipeline[?repo=]` board,
`/tasks/{id}` detail, `/learnings`, `/repositories/new`, plus JSON `/api/summary`,
`/api/tasks`, `/api/tasks/{id}`, `/api/report`, `/api/learnings`, `/api/repositories`.

## Where things are

- `src/main/resources/templates/dashboard.html` — shell, header buttons (Report / Metrics / Theme)
  and the inline theme JS (localStorage key `mend-theme`).
- `src/main/resources/templates/fragments/live.html` — everything below the header: KPI cards,
  pipeline board, issues table, exclusion table, state-transition stream.
- `src/main/resources/static/css/devin.css` — theme tokens: `:root`/`.light`, `.dark`,
  `.high-contrast`. `static/js/theme.js` cycles light → dark → contrast and stores
  `localStorage['mend-theme']`; some pages have no `[data-theme-label]` span so the active
  theme may not be named there even though cycling works.
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

These were true at some point and have since been fixed — re-check them as regressions rather
than reporting them as new:

- `DashboardService.BOARD` skipping states (`UNVERIFIED`, `CHANGES_REQUESTED` had no column, so
  those tasks vanished from the board). Always cross-check board card count against `/api/tasks`.
- `TaskDetail` carrying no verification tier / evidence / verifier session / review rounds even
  though `RemediationTask` persists them.
- `/api/report` covering a single repo, omitting unverified work, and breaking its markdown
  table on `|` inside a reason.
- Missing ids (`/tasks/9999`) falling through to Spring's Whitelabel error page.
- Architecture SVG connector labels overprinting boxes; menD→Devin flows now run through the
  corridor above the boxes and results return along the corridor below them.
- A success rate that ignored unverified attempts.

Still worth judging every run: the task page's verification section is the trust claim, so any
state that says "verified" without a tier, verdict and provenance is a reportable defect.

## Docker packaging

`docker build` compiles Maven inside the image; on this box it fails with HTTP 429 from Maven
Central. If that happens, retry or pass the build arg `MAVEN_MIRROR_URL=<internal mirror>`
(the Dockerfile supports it). Do not run `deploy/demo.sh` while a local instance is up — it
binds port 8080 and the same H2 data dir.

## Devin secrets needed

- `DEVIN_API_KEY`
- `GITHUB_APP_INSTALLATION_ID`
- `GITHUB_APP_PRIVATE_KEY`
