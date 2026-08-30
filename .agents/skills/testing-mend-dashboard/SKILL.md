---
name: testing-mend-dashboard
description: How to run and test the menD (D1) Spring Boot control-plane dashboard end-to-end against a credentialed setup without spending ACUs or triggering real Devin remediation sessions.
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

## Fastest safe way to test (credentialed, no ACU spend)

`deploy/setup.sh` is the one entry point and it starts the *live* stack on port 8080 — engine and
poller on. Never test against that instance. Test against a second one of your own with both loops
off, so no issue is ever dispatched and no ACU is spent:

```
docker compose build
docker run -d --name mend-test -p 8083:8080 -v mend-test-data:/app/data \
  -e MEND_REPO=udaysagarn/superset \
  -e MEND_ENGINE_ENABLED=false -e MEND_POLLING_ENABLED=false \
  -e GITHUB_APP_ID="$GITHUB_APP_ID" -e GITHUB_APP_INSTALLATION_ID="$GITHUB_APP_INSTALLATION_ID" \
  -e GITHUB_APP_PRIVATE_KEY="$GITHUB_APP_PRIVATE_KEY" \
  -e DEVIN_ORG_ID="$DEVIN_ORG_ID" -e DEVIN_API_KEY="$DEVIN_API_KEY" mend-orchestrator:local
```

With the credentials present the registry validates for real (`accessState: VALIDATED`), which is
what the repository cards and the credential alarm are read against. Task rows have to be seeded
directly (below) — with the poller off nothing is ingested, which is the point.

Compose pins the container name, port 8080 and the `mend-data` volume, so a *second*
revision has to run beside it with its own name, port and volume:

```
docker build -t mend-orchestrator:fix .
docker run -d --name mend-fix -p 8084:8080 -v mend-fix-data:/app/data \
  -e MEND_ENGINE_ENABLED=false -e MEND_POLLING_ENABLED=false mend-orchestrator:fix
```

The slower fallback, without Docker:

```
MEND_ENGINE_ENABLED=false MEND_POLLING_ENABLED=false \
  java -jar target/mend-orchestrator-0.1.0.jar    # rebuild with `mvn -B -DskipTests package` if stale
```

With no GitHub key present the app logs "GitHub token not configured: ingestion and issue
feedback are disabled" and cannot make outbound calls — the safest possible test posture.
Use `setsid nohup ... &` to start it; a `pkill -f mend-orchestrator && java -jar ...` chain in
one shell command can kill the shell before the restart runs. Safest sequence is three separate
shell calls: `pkill -f mend-orchestrator`, then
`setsid nohup env ... java -jar ... > /tmp/mend.log 2>&1 < /dev/null & disown`, then poll
`curl -s -o /dev/null -w "%{http_code}" localhost:8080/` until it returns 200 (~20-25s).

**Always rebuild before `java -jar`.** `target/mend-orchestrator-0.1.0.jar` is often left over from
an earlier branch, and templates/CSS are packaged *inside* it — so a Thymeleaf- or CSS-only change
(headers, nav, themes) is completely invisible if you run a stale jar, and the test will
"pass" against the old UI. Run `mvn -B -DskipTests package` (~1-3 min) and check the jar's mtime
before starting. `mvn spring-boot:run` avoids the trap entirely but holds the shell.

## Run the jar from a copy, not from `target/`

A running `java -jar target/mend-orchestrator-0.1.0.jar` reads classes and static resources
*lazily out of that file*. If anyone (the lead agent, another session, an IDE) runs `mvn package`
while it is up, the jar is replaced underneath the JVM and the instance half-dies: already-parsed
pages still render, but every not-yet-read resource fails. Symptoms seen: `/img/*.svg` and
`/css/*.css` requests hang until the client times out, `<object>` diagram embeds render as empty
boxes, and the log fills with
`NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy`. That looks exactly like a
broken-diagram UI bug, so before reporting one, check `ls -la target/*.jar` against the app's start
time and `curl -m 5 -o /dev/null -w '%{http_code}' localhost:<port>/img/architecture.svg` (a healthy
instance answers 200 in milliseconds). Avoid it altogether:

```
cp target/mend-orchestrator-0.1.0.jar /tmp/mendjar/app.jar
setsid nohup env MEND_ENGINE_ENABLED=false MEND_POLLING_ENABLED=false SERVER_PORT=8083 \
  java -jar /tmp/mendjar/app.jar > /tmp/mend.log 2>&1 < /dev/null & disown
```

`SERVER_PORT=<n>` is enough to move a jar run off 8080 — no need for Docker just to get a second
instance. Note `pkill -f mend-orchestrator` in a compound shell command kills the calling shell
too; run it as its own call, and it will not match a jar copied to another name.

## Judging the diagrams (`static/img/*.svg`)

`.diagram object` has `width="100%"` and no height, so the SVG is *scaled down* to the container —
it never gets a horizontal scrollbar, and the 1460px-wide architecture diagram renders at ~72% on
`/` and at ~46% in a 1024px-wide `/deck` window, putting 10.5px captions at ~5-8px on screen.
Screenshots at browser scale therefore prove nothing about legibility; use a full-resolution `zoom`
on regions instead.
Collisions are much easier to prove from the source than by eye — parse the `<rect>`s and the
`<path d="M …">` segments and report any axis-aligned segment whose span crosses a box rect, plus
label bboxes overlapping rects. That catches connectors drawn straight through a box, which is the
defect class this diagram has regressed into before.

## Deck slide checks (`/deck`)

`static/js/deck.js` sets `[data-slide-count]` from `document.querySelectorAll(".slide").length`, so
the header `n / N` is a free assertion on slide count. Slides are `display:none` except `.current`,
arrow keys/PageUp/PageDown/Home/End all work, and a **click anywhere in the right two-thirds of the
slide advances it** — so clicking the slide "to give it focus" silently moves you on; press `Home`
after clicking. Keyboard navigation needs no focus at all (the listener is on `document`).

## Seeding demo data into H2 directly

This is the only way to get task rows without dispatching real work, so every board, task-page and
report test starts here. Cover the states the UI has been wrong about before — `UNVERIFIED`,
`CHANGES_REQUESTED`, `NOT_A_CANDIDATE` — and give at least one row a `prUrl` so the task page's
Pull request link can be exercised.

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
- `RepositoryBootstrap.seed()` runs on `ApplicationReadyEvent`, a beat *after*
  `/actuator/health` goes green. Reading `/api/repositories` the instant health passes shows
  an empty registry that fills a second later — wait before concluding anything about it.

## The registry must survive a bad credential

A repository is never dropped because menD could not talk to GitHub about it: whatever the
verdict, the row persists with `NO_ACCESS` and a reason the operator can act on. Reproduce
safely (no polling, no spend, real installation id and key with a deliberately wrong app id):

```
docker run -d --name mend-badcred -p 8083:8080 -v mend-badcred-data:/app/data \
  -e MEND_ENGINE_ENABLED=false -e MEND_POLLING_ENABLED=false \
  -e GITHUB_APP_ID=999999 -e GITHUB_APP_INSTALLATION_ID="$GITHUB_APP_INSTALLATION_ID" \
  -e GITHUB_APP_PRIVATE_KEY="$GITHUB_APP_PRIVATE_KEY" mend-orchestrator:local
```

`/` must show the repo card with a red `NO_ACCESS` pill and "menD could not ask GitHub about
… GitHub answered 401 … then re-validate", and `/repositories/new` the same row under "Already
registered". An empty registry with only a `could not register` WARN in the log is the
regression (fixed once: the exception escaped the transactional `register()` and rolled the
seeded row back).

The stored `accessError` names only the *shape* of the failure (`GitHub answered 401`,
`GitHub could not be reached`, `the request failed (X)`) because it is rendered on pages
nothing authenticates. Raw exception text (`401 Unauthorized: [no body]`), request URLs,
`Bearer …` or a JWT appearing on `/`, `/flows`, `/learnings`, `/repositories/new`,
`/fragments/live` or `/api/repositories` is a leak worth reporting — grep those routes for
those strings, do not eyeball it.

## The credential alarm (banner on every page)

`CredentialHealth` + `CredentialAdvice` push `credentialProblems` into every `DashboardController`
view, and `templates/fragments/alerts.html` renders the red "Credentials failing" banner with
"N things are stopping menD from working", a "How to fix" link and a per-repository `Re-validate`
button that POSTs the slug to `/repositories`. Four states worth covering, each needing its own
container (name/port/volume of its own; never touch port 8080):

- bad credential (recipe above): banner names `<slug> · Not visible to menD` with the sanitized
  reason and a `Re-validate` button. Note a container without `DEVIN_API_KEY` also reports
  "Devin credentials are not configured", so the expected count is usually 2, not 1.
- no `GITHUB_APP_*`/Devin env at all: exactly the two "not configured" problems, no per-repo
  entry (suppressed on purpose — the repo still shows `NO_ACCESS` further down the page) and no
  `Re-validate` button.
- Devin refused the key: the `devin_credential` row (id 1) is written by a 401/403 from a call menD
  was already making, so a *present but invalid* key shows nothing until the first dispatch. Force
  the state directly (a one-row insert / `update devin_credential set usable=false, reason='…'`)
  rather than waiting on a dispatch; expect "Devin refused menD's credential" with the status and
  **no** `Re-validate` button.

Because the banner also sits inside the htmx-polled `fragments/live`, `/flows` must be watched
for >10s (2+ polls) before concluding either way: a broken fragment would blank the board or drop
the banner on the first swap. Confirm the polls really happened via
`http_server_requests_seconds_count{uri="/fragments/live"}` on `/actuator/prometheus`.

## Routes (current product revision)

`/` product overview (architecture SVG + repo cards), `/flows[?repo=]` board,
`/tasks/{id}` detail, `/learnings`, `/repositories/new`, plus JSON `/api/summary`,
`/api/tasks`, `/api/tasks/{id}`, `/api/report`, `/api/learnings`, `/api/repositories`.

## Where things are

- `src/main/resources/templates/dashboard.html` — shell, header buttons (Report / Metrics / Theme)
  and the inline theme JS (localStorage key `mend-theme`).
- `src/main/resources/templates/fragments/live.html` — everything below the header: KPI cards,
  flow board, issues table, exclusion table, state-transition stream.
- `src/main/resources/static/css/devin.css` — theme tokens: `:root`/`.light`, `.dark`,
  `.high-contrast`. `static/js/theme.js` cycles light → dark → contrast and stores
  `localStorage['mend-theme']`; some pages have no `[data-theme-label]` span so the active
  theme may not be named there even though cycling works.
- `src/main/java/ai/devin/mend/web/DashboardService.java` — the `BOARD` map defines the board
  columns (currently eight: Triage, Ready, Devin working, Verifying, In review, Done, Unverified,
  Excluded / escalated) and the `Kpis` record defines every number the UI and `/api/report` show.
- `src/main/resources/templates/deck.html` — the `/deck` pitch, 12 fragment-addressed slides. The
  live-numbers slide (currently 7) holds the recent-finished-tasks table and, when nothing has
  finished, the "Label an issue `menD:fix`" empty-state callout.

## Non-mutating verification surface

Everything can be verified without mutating state:

```
curl -s localhost:8080/api/summary        # KPI ground truth, compare against the cards
curl -s localhost:8080/api/tasks          # per-issue rows incl. state/session/PR/note
curl -s localhost:8080/api/tasks/{id}/events   # full state history for one task
curl -s localhost:8080/api/report         # markdown leadership report
curl -s localhost:8080/actuator/prometheus | grep '^mend_'
```

## Exercising the reviewer loop

There is no way to play the reviewer from outside without a real pull request, and a real review
hands the task back to a real Devin session. Leave it to `ReviewLoopTest` and the rest of
`mvn -B verify`; on the UI side, seed a `CHANGES_REQUESTED` row and check the board column, the
task page's review rounds and `/learnings` render it. The handback takes ~1s in any case, so the
*In review* column is essentially never caught by the 5s fragment poll — read the on-page
**State transitions** stream instead of chasing the card.

## Proving auto-refresh actually polls

The dashboard uses htmx `hx-get="/fragments/live" hx-trigger="every 5s"`. Since the data is
static during a non-mutating test, a screenshot cannot distinguish "refreshing" from "frozen".
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
(the Dockerfile supports it). Do not run `deploy/setup.sh` while a local instance is up — it
binds port 8080 and the same H2 data dir.

## Browser quirks on this box

Chrome's omnibox drops the `:` when `localhost:8080` is typed in one go; type the full
`http://127.0.0.1:8080/...` instead. It also drops a trailing `#N` fragment (typing
`http://127.0.0.1:8083/deck#7` navigates to `/deck7`, a 404). Load `/deck` and page through the
slides with `Page_Down`/`Page_Up` (click the slide body first so it has focus); the header shows
`n / 12` and the URL fragment follows.

## Bind-mounted H2 data directories

If you bind-mount a host directory for H2 (handy when you want to seed with the H2 Shell from the
host) the container user may not be able to write it — the container exits with
`AccessDeniedException: /app/data/mend.lock.db`. Give the directory to the image's own user —
`sudo chown -R 10001 <hostdir>` (uid 10001 is `mend` in the Dockerfile; `-R`, or host-seeded database
files stay unwritable) — or use a named docker volume instead. Do not `chmod 777` it: every local
user could then rewrite the database under the test.

## Seeding gotcha: enum values inside JSON columns

`verification_json` / `feedback_json` are deserialised into `Verification` / criteria records, so an
invented enum value makes the task page blow up rather than degrade. Valid tiers are `REPO_CI`,
`CONTRACT_WORKFLOW`, `VERIFIER_SESSION`, `NONE`; valid verdicts are `PASSED`, `FAILED`, `PENDING`,
`UNAVAILABLE` (there is no `INCONCLUSIVE`). Field names in those JSON blobs are snake_case.

## Exercising `deploy/setup.sh` without touching the real stack

`setup.sh` does `cd "$(dirname "$0")/.."`, so a throwaway probe must mirror the repo layout:
`<probe>/deploy/setup.sh` plus `<probe>/.env.example`. Then
`ENV_FILE=./env.test PORT=9099 bash ./deploy/setup.sh` with the answers piped in exercises the
prompting (bad Devin key rejected and re-prompted, empty webhook secret auto-generated, PEM path
inlined as a `\n`-escaped one-liner) and fails at `docker compose up` with `no configuration file
provided` — a non-zero exit, which is the expected outcome. It never touches the real `.env` or
port 8080. Always confirm afterwards with `git status --short` and `ss -ltn | grep :8080`.

## When the credentialed happy path will not validate

`accessState` stuck on `NO_ACCESS` with real credentials is usually a *stale installation id*, not a
bug: `GITHUB_APP_INSTALLATION_ID` in the environment can belong to an installation that was deleted
and recreated. Distinguish the two read-only, without dispatching anything, by minting the App JWT
yourself and asking GitHub which installations the App actually has:

```
GET /app                         -> 200 + the app slug proves app id + private key are good
GET /app/installations           -> the live installation ids for that app
GET /app/installations/{id}      -> 404 means the configured id is not this app's
GET /installation/repositories   -> (with an installation token) the repos it can see
```

Two shape notes for that script: the stored PEM may arrive with newlines flattened to spaces, so
re-wrap the base64 body at 64 chars before `jwt.encode`, and menD maps a 401 to "GitHub answered
401 …" but a 404 to "menD cannot see `<slug>` … install the menD GitHub App on this repository" —
the wording tells you which failure you have. Once the right installation id is used the card turns
green `VALIDATED` / `operational: true` within a couple of seconds of health, with no banner.

## Devin secrets needed

- `DEVIN_API_KEY`
- `DEVIN_ORG_ID`
- `GITHUB_APP_ID`
- `GITHUB_APP_INSTALLATION_ID` (may be stale — see the section above)
- `GITHUB_APP_PRIVATE_KEY`
