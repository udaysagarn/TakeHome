# menD sitemap

Every route the application serves, read from the controllers rather than from memory. Written for
an audit: each row says who serves it, what it renders, what it links to, and whether anything in the
UI actually reaches it.

Controllers: `web/DashboardController` (pages), `web/ApiController` (JSON under `/api`),
`web/MendErrorController` (`/error`), `ingest/WebhookController` (`/webhooks/github`).

## Pages (HTML)

Every page renders the same header navigation from `templates/fragments/nav.html` (`links(active)`):
Overview, then three menus — Flows (Board, Learnings), Repositories (Register a repository,
Registered repositories JSON) and More (Deck, Report, Metrics) — then the theme control. The menus
are `<details>`, so they work without JavaScript; `static/js/nav.js` only closes them on an outside
click or Escape. The page you are on is marked with `aria-current="page"` and its menu with
`aria-current="true"`, instead of linking to itself; a page with no entry of its own (a task, an
error) marks nothing. Page-specific controls (the repository switch on the board,
Issue/Pull request on a task, Print on the deck) sit beside that nav, never in place of it.

| Route | Template | Purpose | Linked from |
|---|---|---|---|
| `GET /` | `overview.html` | Landing page: what menD is, repo cards, KPIs, architecture + flywheel diagrams | nav on every page |
| `GET /flows?repo=` | `dashboard.html` | The board — tasks by state, KPI cards, live refresh. `repo` filters to one repository; absent means all | nav on every page, repo cards, task rows |
| `GET /tasks/{id}` | `task.html` | One task: criteria contract, Devin sessions, attempts, ACU, lease, event history, verification evidence | `/flows` rows |
| `GET /learnings` | `learnings.html` | What reviewers taught menD: repo-scoped and general lessons, recommended actions, retired lessons | nav on every page |
| `GET /repositories/new` | `register.html` | Step-by-step registration instructions plus the register form | nav on every page, `/` |
| `POST /repositories` | `register.html` | Registers + validates a repository, re-renders the same page with the verdict | form on `/repositories/new` |
| `POST /engine` | — (redirects back) | Pauses or resumes new work | pause switch in the navigation |
| `GET /deck` | `deck.html` | The pitch as slides (Problem / Why now / Product / Devin at work / Architecture / Why Devin / What's next). Numbers come from this instance's database | nav on every page |
| `GET /fragments/live?repo=` | `fragments/live :: live` | htmx polling target — everything below the header on the board. Not a page a human opens | htmx on `/flows` |
| `RequestMapping /error` | `error.html` | Branded error page for 404/500 | Spring's error dispatch |

## JSON API — `/api`

| Route | Returns |
|---|---|
| `GET /api/summary` | KPI counters for the whole instance |
| `GET /api/states` | Task counts per state |
| `GET /api/engine` | Whether menD may start work that spends, and who paused it |
| `POST /api/engine?paused=&reason=&actor=` | Pauses or resumes new work |
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
  (`POST /api/repositories`, `POST /api/tasks/{id}/cancel`, `POST /api/issues/{number}/ingest`,
  `POST /api/engine`, `POST /engine`), is open to whoever can reach the port. Only `/webhooks/github` verifies anything, and only that the
  payload came from GitHub. This is documented in the wiki under Errors and limits; it is the single
  biggest gap before anyone exposes menD beyond a laptop or a private network.
- **Reachable only by URL, not by link:** `/api/*` (except the report and Prometheus links in the
  footer) and `/fragments/live`. That is intentional: both are called by the pages, not opened by a
  human.
- **`POST /repositories` and `POST /api/repositories` do the same thing** through different content
  types — the form path re-renders `register.html`, the API path returns JSON.
- **`/deck` reads live instance data**, so an empty database renders a deck with zeroes; it is not a
  static asset.
