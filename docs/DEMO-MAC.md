# Demoing menD on a Mac

Three ways to run it, in increasing order of what they prove:

| Mode | Needs | Proves |
|---|---|---|
| Read-only | nothing | the product, the board, the diagrams |
| Sandbox | nothing | the whole workflow end to end, including the paths that fail |
| Live | Devin key + GitHub App | that Devin actually writes the fix and a real reviewer sees it |

Prerequisites for all three: Docker Desktop (Apple silicon is fine — the image is built from source, so
there is no architecture to match), Git, and port 8080 free. Nothing else: the JDK and Maven live inside
the build stage.

```bash
git clone https://github.com/udaysagarn/menD.git
cd menD
```

If the build stops on `429 Too Many Requests` from Maven Central, it retries through a read-only mirror by
itself. Behind a corporate proxy, point it at yours: `docker compose build --build-arg
MAVEN_MIRROR_URL=https://your/mirror`.

---

## 1. Read-only — five minutes, no credentials

```bash
./deploy/demo.sh
```

With no `.env` present this writes one with `MEND_ENGINE_ENABLED=false` and `MEND_POLLING_ENABLED=false`, so
menD never calls GitHub and never spends an ACU. Open http://localhost:8080 and walk `/` → `/repositories/new`
→ `/pipeline` → `/learnings` → `/api/report`.

Good for: explaining the product. Not good for: showing anything move.

## 2. Sandbox — the whole workflow, still no credentials

```bash
./deploy/simulate.sh
```

The `sandbox` profile replaces the GitHub client and the Devin client with in-memory simulations and files four
issues, one per scenario. Everything between those two edges is the real control plane. Open
http://localhost:8080/pipeline and watch:

- a clean fix land and be proved by the (simulated) repository CI,
- an issue that needs a product decision be declined as `menD:not-a-candidate` before a remediation session is
  ever created,
- a fix that nothing can verify land as `UNVERIFIED` rather than being counted as a success,
- a reviewer reject a pull request, the same Devin session answer the feedback, and the lesson appear on
  `/learnings`.

Issue and pull request links on the board are safe to click here: in the sandbox they point at menD's own
`/sandbox/...` pages, which show the simulated issue, the branch, the checks and the reviews, rather than at a
github.com url whose number does not exist.

Play the reviewer yourself against any open simulated pull request:

```bash
curl localhost:8080/api/sandbox           # find the pull request numbers
curl -X POST localhost:8080/api/sandbox/pulls/9001/request-changes \
     -H 'content-type: application/json' \
     -d '{"reviewer":"you","body":"add a spec next to the component"}'
```

This is the mode to rehearse a presentation in: it is deterministic, it takes about a minute, and it shows the
uncomfortable outcomes as readily as the happy one.

## 3. Live — Devin does the engineering

```bash
cp .env.example .env      # DEVIN_API_KEY, DEVIN_ORG_ID, GITHUB_APP_*, MEND_REPO
docker compose up -d --build
```

Creating those four things takes about fifteen minutes and is written out click by click in
[docs/CREDENTIALS.md](CREDENTIALS.md), including the exact GitHub App permissions and how to check them before
an audience is watching.

Then label a real issue `menD:fix` in a registered repository. menD scopes it in one Devin session, writes the
acceptance criteria as a comment, dispatches a second session to do the work, waits for independent
verification, and reports back on `/pipeline`. Label a vague issue too — the contrast between the two is the
demo. The vetted set of issues in the superset fork, and which outcome each one is there to show, is in
[docs/DEMO-ISSUES.md](DEMO-ISSUES.md).

Budget: the scoping session is capped at 3 ACUs and the remediation session at 10 (`MEND_CRITERIA_ACU`,
`MEND_REMEDIATION_ACU`).

---

## Running it

| | |
|---|---|
| Logs | `docker compose logs -f mend` |
| Stop, keep history | `docker compose down` |
| Stop, forget everything | `docker compose down -v` |
| Port 8080 taken | `PORT=9090 docker compose up -d` and use `BASE=http://localhost:9090` with the scripts |
| Reset just the demo data | `docker compose down -v && ./deploy/simulate.sh` |

State lives in the `mend-data` Docker volume as a file-backed H2 database, so a restart keeps the board, the
audit trail and the learnings. Secrets are read from the environment only; nothing is written to the image, the
repository or the database.

## Contributing

```bash
mvn -B verify                                  # the full suite, with a coverage floor
SPRING_PROFILES_ACTIVE=sandbox mvn spring-boot:run
curl -X POST localhost:8080/api/sandbox/issues/all
```

The sandbox is the fast feedback loop for orchestrator changes: a new state, a different gate rule or another
verification tier can be exercised in seconds without touching GitHub. The simulations themselves live in
`src/main/java/ai/devin/mend/sandbox` — `SandboxHub` is the fake GitHub, `SandboxDevinClient` is the scripted
Devin, and `SandboxController` is the contributor's control surface. Add a scenario by adding a value to
`SandboxScenario` and the matching branch in those two classes.
