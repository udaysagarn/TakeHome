# Demoing menD on a Mac

One mode, and it is the live one: Devin writes the fix and a real reviewer sees it. menD needs a Devin key and
a GitHub App before it can do anything, so the demo starts by creating them.

Prerequisites: Docker Desktop (Apple silicon is fine — the image is built from source, so there is no
architecture to match), Git, and port 8080 free. Nothing else: the JDK and Maven live inside the build stage.

```bash
git clone https://github.com/udaysagarn/menD.git
cd menD
./deploy/setup.sh
```

`deploy/setup.sh` is the single entry point. It asks for each credential it cannot invent, writes `.env`,
runs `docker compose up -d --build`, and waits for `/actuator/health`. Creating the credentials takes about
fifteen minutes and is written out click by click in [docs/CREDENTIALS.md](CREDENTIALS.md), including the exact
GitHub App permissions and how to check them before an audience is watching.

If the build stops on `429 Too Many Requests` from Maven Central, it retries through a mirror by itself.
Behind a corporate proxy, point it at yours: `docker compose build --build-arg
MAVEN_MIRROR_URL=https://your/mirror`.

---

## The demo

Open http://localhost:8080 and walk `/` → `/repositories/new` → `/flows` → `/learnings` → `/api/report`.
`udaysagarn/superset` is pre-registered, so the board and the registry are populated on first boot.

Then label a real issue `menD:fix` in a registered repository. menD scopes it in one Devin session, writes the
acceptance criteria as a comment, dispatches a second session to do the work, waits for independent
verification, and reports back on `/flows`. Label a vague issue too — the contrast between the two is the
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
| Port 8080 taken | `PORT=9090 docker compose up -d` and use `BASE=http://localhost:9090` with the script |
| Start over | `docker compose down -v && ./deploy/setup.sh` |

State lives in the `mend-data` Docker volume as a file-backed H2 database, so a restart keeps the board, the
audit trail and the learnings. Secrets are read from the environment only; nothing is written to the image, the
repository or the database.

## Contributing

```bash
mvn -B verify                # the full suite, with a coverage floor
mvn spring-boot:run          # needs the same environment variables as the container
```

The engine and the poller can be left off (`MEND_ENGINE_ENABLED=false MEND_POLLING_ENABLED=false`) while
working on the dashboard, so a UI change costs no ACUs. Anything that touches the orchestrator is covered by
the test suite rather than by a live run: `mvn -B verify` exercises the state machine, the gate, the leases,
the verification tiers, the review loop and the learning store.
