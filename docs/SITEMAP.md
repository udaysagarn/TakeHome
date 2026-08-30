# menD sitemap

Every route the application serves, read from the controllers rather than from memory. Written for
an audit: each row says who serves it, what it renders, what it links to, and whether anything in the
UI actually reaches it.

Controllers: `web/DashboardController` (pages), `web/ApiController` (JSON under `/api`),
`web/MendErrorController` (`/error`), `ingest/WebhookController` (`/webhooks/github`),
`sandbox/SandboxController` + `sandbox/SandboxPagesController` (sandbox profile only).

## Pages (HTML)

| Route | Template | Purpose | Linked from |
|---|---|---|---|
| `GET /` | `overview.html` | Landing page: what menD is, repo cards, KPIs, architecture + flywheel diagrams | brand mark on every page |
| `GET /pipeline?repo=` | `dashboard.html` | The board — tasks by state, KPI cards, live refresh. `repo` filters to one repository; absent means all | `/`, header on every page, repo cards, task rows |
| `GET /tasks/{id}` | `task.html` | One task: criteria contract, Devin sessions, attempts, ACU, lease, event history, verification evidence | `/pipeline` rows |
| `GET /learnings` | `learnings.html` | What reviewers taught menD: repo-scoped and general lessons, recommended actions, retired lessons | header |
| `GET /repositories/new` | `register.html` | Step-by-step registration instructions plus the register form | `/`, header |
| `POST /repositories` | `register.html` | Registers + validates a repository, re-renders the same page with the verdict | form on `/repositories/new` |
| `GET /deck` | `deck.html` | The pitch as slides (What / How / Why Devin / When). Numbers come from this instance's database | header |
| `GET /fragments/live?repo=` | `fragments/live :: live` | htmx polling target — everything below the header on the board. Not a page a human opens | htmx on `/pipeline` |
| `RequestMapping /error` | `error.html` | Branded error page for 404/500 | Spring's error dispatch |

## Sandbox pages — `sandbox` profile only

Both controllers are `@Profile("sandbox")`, so a credentialed deployment cannot serve them at all.

| Route | Template | Purpose |
|---|---|---|
| `GET /sandbox/{owner}/{name}/issues/{number}` | `sandbox-issue.html` | Simulated GitHub issue with menD's comments and the scenario it plays |
| `GET /sandbox/{owner}/{name}/pull/{number}` | `sandbox-pull.html` | Simulated pull request: head branch, checks, reviews, the issue behind it |

These are what task pages link to in sandbox mode (`issueUrl` / `prUrl` on the task), which is why a
demo click never leaves the app for a github.com 404. A number that was never minted, or the right
number under the wrong repo slug, 404s rather than leaking another repository's data.

## JSON API — `/api`

| Route | Returns |
|---|---|
| `GET /api/summary` | KPI counters for the whole instance |
| `GET /api/states` | Task counts per state |
| `GET /api/tasks` | Task rows |
| `GET /api/tasks/{id}` | One task's full detail (404 if unknown) |
| `GET /api/tasks/{id}/events` | That task's state-transition history |
| `POST /api/tasks/{id}/cancel` | Cancels a task |
| `POST /api/issues/{number}/ingest?repo=` | Ingests an issue by number as if the trigger label had been applied; `202` with the task key, `404` if GitHub doesn't have it |
| `GET /api/repositories` | Registered repositories |
| `POST /api/repositories` | Registers one |
| `POST /api/repositories/{id}/validate` | Re-runs the access/permission check |
| `GET /api/learnings` | `active`, `recommendedActions`, `retired` |
| `GET /api/report` | The run report as `text/markdown` |

## Sandbox API — `sandbox` profile only

| Route | Purpose |
|---|---|
| `GET /api/sandbox` | The simulated surface: repository, scenarios, current hub state |
| `POST /api/sandbox/issues?scenario=&repo=` | Files one simulated issue (default `CLEAN_FIX`) |
| `POST /api/sandbox/issues/all` | One issue per scenario — the fastest way to fill the board |
| `POST /api/sandbox/pulls/{pullNumber}/request-changes` | Play the reviewer; `404` for a pull request that was never opened |

## Machine endpoints

| Route | Purpose |
|---|---|
| `POST /webhooks/github` | GitHub App webhook ingest (issues, push, pull_request, review, review comment). HMAC-verified against `GITHUB_WEBHOOK_SECRET` |
| `GET /actuator/health` | Liveness — what the Docker healthcheck polls |
| `GET /actuator/info` | Build info |
| `GET /actuator/metrics`, `GET /actuator/prometheus` | Metrics; the Prometheus one is linked from the footer |

Exposure is pinned to `health,info,prometheus,metrics` in `application.yml`; nothing else on the
actuator is reachable.

## Static assets

`/css/devin.css`, `/css/deck.css`, `/js/theme.js`, `/js/deck.js`, `/img/architecture.svg`,
`/img/flywheel.svg`, `/img/favicon.svg`.

## Audit notes

- **No authentication anywhere.** Every page and every `/api` route, including the mutating ones
  (`POST /api/repositories`, `POST /api/tasks/{id}/cancel`, `POST /api/issues/{number}/ingest`), is
  open to whoever can reach the port. Only `/webhooks/github` verifies anything, and only that the
  payload came from GitHub. This is documented in the wiki under Errors and limits; it is the single
  biggest gap before anyone exposes menD beyond a laptop or a private network.
- **Reachable only by URL, not by link:** `/api/*` (except the report and Prometheus links in the
  footer), `/fragments/live`, and the sandbox routes. That is intentional for the fragment and the
  API, but it means the sandbox pages are only discoverable through a task's issue/PR link.
- **`POST /repositories` and `POST /api/repositories` do the same thing** through different content
  types — the form path re-renders `register.html`, the API path returns JSON.
- **`/deck` reads live instance data**, so an empty database renders a deck with zeroes; it is not a
  static asset.
