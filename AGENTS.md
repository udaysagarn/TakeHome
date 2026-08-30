# AGENTS.md — working on menD

This is the brief a new contributor (human or agent) should read before touching this repository. It
records how we work, what menD is, what is deliberately *not* built yet, and where the sharp edges are.

`CLAUDE.md` and `DEVIN.md` point here; this file is the single source.

---

## 1. How we work (non-negotiable)

These rules came out of building menD and they outrank convenience every time.

1. **Pull requests as small as possible — one session = one PR = one concern.** Decompose *upfront*.
   This repo already paid for ignoring that once: a single large branch was merged, the incremental
   series underneath it had to be reverted (PR #9) and re-landed as six layers. The seams that became
   PRs #2–#7 (control plane → leases → registry → verification → review/learning → docs) existed from
   the start; they were found after the revert rather than before it. If a change grows a second
   idea, cut a second PR.
2. **Open the PR early and keep it small.** Ask Devin to raise the pull request as soon as there is
   something to look at, so review comments arrive while the change is still small enough to reason
   about. A review on a 3,000-line branch is not a review.
3. **Clarify assumptions — out loud, in writing.** State the assumption in the PR description or the
   issue before you build on it. An assumption nobody can see is a bug with a delay fuse.
4. **Clarify before writing code.** Ambiguity is resolved with the requester, not guessed at in an
   editor. Half of the rework here came from starting to type too early.
5. **Tests first, then high-quality code.** Write the failing test that describes the behaviour, then
   make it pass. `mvn -B verify` enforces a coverage floor; a behavioural change without a test is
   not finished. Never weaken or delete a test to make a build green.
6. **"Untested" in an agent's own summary is a task list, not a footnote.** Every gap Devin admits to
   gets a dispatched follow-up. The three that were left as caveats here — the `docker build`, the
   empty `/learnings` state, and the schema migrations — each turned out to hide a real defect once
   someone actually ran them.
7. **Front-load the SKILL / AGENTS file.** Write the instructions *before* the big session, not
   inside it. `.agents/skills/testing-mend-dashboard/SKILL.md` was worth its weight, and was written
   in the middle of PR #1; writing it first would have made the whole run cheaper and every
   verification pass consistent with the last.

Other conventions that follow from those:

- Commit messages are plain English sentences describing intent ("Let migrations own the schema, so a
  new state can't fail to insert"), not `feat:`-prefixed labels.
- Never claim something works without evidence you can point at — the same standard menD applies to
  Devin (`SUCCEEDED` vs `UNVERIFIED`) applies to us.
- Documentation is written *from the code*: read the controllers, then write the page. Nothing
  aspirational in `docs/`.

---

## 2. What menD is

An event-driven control plane that turns a GitHub label into a verified pull request.

> Devin is the engineer. menD is everything a team needs *around* an engineer to trust the output:
> candidacy, budgets, leases, retries, verification, review response, and an audit trail.

The narrow claim, which everything else exists to protect: *for issues with a machine-checkable
definition of done, the cost of a fix drops to a few ACUs and zero engineer-hours, and the risk stays
bounded because nothing is called done without independent evidence.*

### Mental models

- **The gate is the product.** Anyone can dispatch an agent at every issue; the value is in refusing
  the ones with no verifiable definition of done, *before* spending anything. `NOT_A_CANDIDATE` with
  reasons is a good outcome.
- **Evidence outranks assertion.** The session that wrote the code cannot be the one that certifies
  it. Hence the verification tiers, and `UNVERIFIED` as a first-class terminal state rather than a
  rounding error in the success rate.
- **The database is the system of record.** GitHub labels are a human-visible *projection* of state,
  never an input to it. Webhook deliveries are hints; a lost or duplicated delivery must change
  nothing, because the poller reconciles from GitHub's actual state.
- **A rejection is a signal, not a failure.** `CHANGES_REQUESTED` routes reviewer feedback back into
  the *same* Devin session (it still holds the context of the change it wrote), and the retrospective
  turns the exchange into a durable lesson.
- **Humans own promotion.** menD proposes lessons; a named human approves anything that escapes menD
  into org-wide Devin knowledge, playbooks, or a repo's own instruction files.
- **Honest numbers or none.** The dashboard reports declined work and unverified work as prominently
  as successes.

---

## 3. Code map

Spring Boot 3.3 / Java 21 / Maven. Thymeleaf + htmx for the UI, no build step for the frontend.

| Package | What lives there |
|---|---|
| `domain` | JPA entities and the state machine. `IssueState.canTransitionTo` is the single authority on legal transitions |
| `engine` | `Orchestrator` (drives one task), `Reconciler` (the loop), `TaskService` (**the only writer**), `LeaseManager`, `EngineControl` (the pause switch), `PromptBuilder`, `Verifier`, `Notifier` |
| `triage` | `PreFilter` (free, deterministic) and `SuccessCriteriaService` (the criteria contract + scoping session) |
| `devin` | `DevinApiClient` — session create/poll/message, structured output, ACU limits; `DevinCredentialMonitor` turns a refused call into the verdict the alarm reads |
| `github` | `GitHubClient`, `GitHubCredentials` (App JWT → installation token), DTOs |
| `ingest` | `WebhookController` (HMAC verified) and `IssuePoller` (works with no ingress) |
| `registry` | `RepositoryService` (registration + access validation), `ContextService`/`ContextReconciler` (repository profile, incrementally refreshed on push) |
| `learning` | `ReviewLoop` (reviewer feedback in) and `LearningService` (lessons out, dedup, retire) |
| `web` | Dashboard, JSON API, report, error page |
| `config` | `MendProperties` — every tunable, bound from `mend.*` |

Templates live in `src/main/resources/templates`; the brand mark and favicon are one shared fragment,
`templates/fragments/brand.html` (the wordmark's `D` *is* the remediation loop — do not add a second
`D` alongside it).

### State machine

```
DISCOVERED → CRITERIA_PENDING → READY → DISPATCHED → RUNNING ⇄ BLOCKED
                    ↓                                    ↓
             NOT_A_CANDIDATE                          PR_OPEN → VERIFYING → SUCCEEDED
                                                         ↑          ↓          UNVERIFIED
                                              CHANGES_REQUESTED ────┘          FAILED → (retry) DISPATCHED
                                                                                NEEDS_HUMAN / CANCELLED
```

`FAILED` is terminal for the attempt but re-dispatchable while the attempt budget allows. `UNVERIFIED`
can still be upgraded if evidence arrives later.

### Verification tiers

`REPO_CI` → `CONTRACT_WORKFLOW` (`deploy/target-repo/mend-verify.yml`, dispatched by menD) →
`VERIFIER_SESSION` (command-only Devin session, never writes code) → otherwise `UNVERIFIED`.
menD never runs a target repo's test suite inside its own container: one image cannot hold every
toolchain, and it would be an arbitrary-code-execution target.

---

## 4. Running it

```bash
mvn -B verify                # full suite + coverage floor
./deploy/setup.sh            # the one entry point: procures credentials, writes .env, starts the stack
docker compose up -d --build # the same start, once .env is complete
```

There is one mode and it is live: menD needs a Devin key and a GitHub App to do anything, so the
orchestrator's behaviour is pinned by the test suite rather than by a credential-free run. Working on
the dashboard alone, start it with `MEND_ENGINE_ENABLED=false MEND_POLLING_ENABLED=false` so no
issue is dispatched and no ACU is spent.

Docs: `README.md` (the pitch), `docs/SITEMAP.md` (every route, for audit), `docs/DEMO-MAC.md`,
`docs/CREDENTIALS.md`, `docs/DEMO-ISSUES.md`, and the published GitHub Wiki (`docs/wiki/`, 14 pages of
API and contracts written from the controllers).

---

## 5. Sharp edges

- **Schema is owned by Flyway**, per dialect (`db/migration/h2`, `db/migration/postgresql`), with
  `ddl-auto: validate`. Add a migration; never let Hibernate widen a column for you.
- **Enum columns** are mapped `@Enumerated(EnumType.STRING)` + `@JdbcTypeCode(SqlTypes.VARCHAR)` and
  declared `varchar(32)`. Without this, Hibernate emits a native enum column and a *new* state value
  fails to insert — that bug shipped once (`UNVERIFIED`, `CHANGES_REQUESTED`). `SchemaMigrationTest`
  guards it.
- **Timestamp precision**: the review watermark is compared at the precision the database stores, not
  the JVM's. Comparing finer made menD answer the same reviewer forever.
- **A pause is not the kill switch.** `mend.engine.enabled=false` is configuration, read at startup,
  and nothing lifts it but a restart. The pause an operator clicks is a persisted row read every tick,
  and it holds only the states whose next step creates a Devin session (`DISCOVERED`, `READY`,
  `FAILED`, and starting a repository profile). Anything already dispatched keeps being polled: a
  session frozen mid-run still spends and nobody reads the result.
- **`TaskService` is the only writer.** Anything else mutating a task is a bug.
- **Terminal labels**: `settleLabel(...)` removes stale lifecycle labels before applying the terminal
  one, so an issue never carries `menD:changes-requested` *and* `menD:done`.
- **The credential alarm is on every page except `/deck`**, deliberately: the deck is presented to an
  audience, and the operator who can fix a credential is not looking at it. The deck cannot go
  silently false either — its numbers come from the same live query, so a rejected credential shows
  there as zeroes and a `NO_ACCESS` repository card.
- **Maven Central 429s** on shared IPs; the Dockerfile falls back to a mirror (`use-mirror.sh`,
  `MAVEN_MIRROR_URL`).

---

## 6. Decided, specified, deliberately not built

Do not build these without talking to the owner — the shape is agreed, the timing is not.

### 6.1 Lessons become playbooks

A knowledge note is a *fact* ("this repo pins transitive deps in `package.json` overrides"); a
playbook is a *procedure* ("how a dependency bump lands here"). Today menD's remediation prompt is an
ad-hoc invisible playbook, assembled per session and thrown away. Agreed target:

- **Per repository, not per org**, and **two of them**: a remediation playbook (how a fix lands here)
  and a verification playbook (what proof this repo accepts).
- **Human approval always.** menD proposes a playbook diff with the review comment that motivated it;
  a human accepts, edits or rejects. A rejected proposal is not offered again.
- **Versioned, and the version recorded on each task**, so "did review comments drop after v3?" is
  answerable and a bad revision can be rolled back.
- **Humans own the artefact.** A hand edit is authoritative; menD reads it and stops proposing that
  change.

Routing rule for a lesson: repo+factual → repo knowledge note / `AGENTS.md` PR; repo+procedural →
that repo's playbook; general+factual → org-wide knowledge note; general+procedural → org-wide
playbook.

### 6.2 Approve/reject promotion UI on `/learnings`

Each lesson already carries a `RecommendedAction`. What is missing is the control that *performs* it:
`DEVIN_KNOWLEDGE` → write an org-wide Devin knowledge note; `REPO_INSTRUCTIONS` → open a PR appending
the lesson to the target repo's `AGENTS.md`/`CLAUDE.md`; `MEND_BACKLOG` → file an issue against menD;
reject → retire with the reviewer's reason. Every promotion stores approver, timestamp and a link to
the artefact it created.

### 6.3 The strategic flywheel

Distinct from the existing "how menD works" wheel (`static/img/flywheel.svg`), which stays. The
strategic one is the customer-value loop, drawn Amazon-style: an outer ring of six labels plus an
inner loop through the middle, with the outcome word in the centre where Amazon writes "Growth".
Three candidates, none chosen:

| | Loop | Centre |
|---|---|---|
| A · cost | more merged fixes → more reviewer feedback → sharper lessons → higher first-pass acceptance → fewer review rounds → lower cost per merged fix | **Throughput** |
| B · trust | evidence-backed fixes → engineers trust the bot → wider unattended scope → more issues enter the loop → more evidence (counterweight: unverified work is never claimed) | **Autonomy** |
| C · network | more repositories registered → more general lessons → promoted to org-wide Devin knowledge → every session in the org improves → faster time-to-merge everywhere | **Leverage** |

Two failed attempts are worth not repeating: sub-captions under each label make it unreadable, and
arcs that do not start and end *at the labels* stop it reading as a wheel.

---

## 7. Open ToDos

- **No authentication anywhere** except the webhook's HMAC. Every page and every `/api` route,
  including `POST /api/repositories`, `POST /api/tasks/{id}/cancel` and
  `POST /api/issues/{number}/ingest`, is open to whoever reaches the port. Documented in
  `docs/SITEMAP.md` and the wiki; it is the single biggest gap before menD is exposed beyond a laptop
  or a private network.
- **A Devin key is only judged once it is used.** `devin_credential` holds one row, written
  when Devin refuses a call menD was already making (401/403) and cleared by the next call that
  works; the credential alarm reads it. So a mistyped key reads as healthy until the first dispatch,
  and a 404 or an outage is deliberately not a verdict. menD never calls the Devin API to ask.
- **Lesson attribution is per repository, not per injected lesson** — good enough to retire bad
  advice, not precise enough to say which lesson helped.
- **The contract workflow is a starter template**; a repo with an unusual toolchain must adapt its
  setup steps before merging it.
- **Strategic flywheel + final deck** (§6.3) — the deck at `/deck` exists and draws its numbers from
  the live database; the strategy slide is waiting on the flywheel choice.
- **Dark/high-contrast themes** are token-driven and spot-checked, not covered by an automated visual
  test.

---

## 8. Demo assets

- Target repository for demos: the `udaysagarn/superset` fork. Vetted demo issues and how each was
  checked are in `docs/DEMO-ISSUES.md` — they are real (OSV-confirmed pins, a mirrored upstream
  issue), which matters: **only file legitimate issues**.
- Secrets are listed in `.env.example` and created step-by-step in `docs/CREDENTIALS.md`. Never commit
  a key, and never paste one into an issue, PR or log line.
