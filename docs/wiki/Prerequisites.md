# Prerequisites

What has to be true before menD can do anything useful.

## 1. A running menD instance

Java 21 and Maven, or Docker. From the repository root:

```bash
mvn -B spring-boot:run          # http://localhost:8080
# or
docker compose up -d --build
```

Storage is H2 in file mode by default (`./data/mend`), so state survives restarts. Point
`MEND_DB_URL` at Postgres for anything shared.

Nothing below is needed if you only want to see the workflow: the
[sandbox](Sandbox-API) runs the whole pipeline with no credentials and no ACU spend.

## 2. Devin API credentials

| Variable | Required | What it is |
|---|---|---|
| `DEVIN_API_KEY` | yes | API key for your Devin organisation |
| `DEVIN_ORG_ID` | yes | `org-…` identifier; menD calls `/v3/organizations/{org}/sessions` |
| `DEVIN_API_URL` | no | Defaults to `https://api.devin.ai` |

Without these, menD still ingests issues but cannot scope or remediate them.

## 3. A GitHub identity with write access

Either a GitHub App (recommended) or a personal access token (local use).

**GitHub App** — install it on each repository you plan to register.

| Variable | What it is |
|---|---|
| `GITHUB_APP_ID` | Numeric app id |
| `GITHUB_APP_INSTALLATION_ID` | Installation on your org/account |
| `GITHUB_APP_PRIVATE_KEY` | PEM contents of the app private key |

Required installation permissions, checked at registration time:

| Permission | Level | Why |
|---|---|---|
| Issues | Read & write | Read the issue, apply and swap menD's labels, comment |
| Pull requests | Read & write | Read the pull requests Devin opens, comment the evidence |
| Contents | Read | Build the repository profile and read instruction files |
| Checks / Actions | Read | Read CI verdicts on the pull request head |
| Metadata | Read | Mandatory for any app |

A repository whose installation is missing one of these registers with
`accessState: MISSING_PERMISSION` and an `accessError` naming the missing scope — see
[Registering a repository](Registering-a-Repository).

**Token fallback** — set `GITHUB_TOKEN` instead. GitHub does not report a token's scopes, so menD
cannot pre-check permissions; failures surface later as API errors instead of at registration.

## 4. The repository itself

- Issues must be enabled. Issues are the trigger; a repository with issues turned off fails
  validation.
- It must not be archived or disabled.
- If you use a GitHub App with *selected* repositories, the repository must be one of them.
- The repository does **not** need CI. If it has none, menD falls back through the
  [verification tiers](Verification-and-Evidence) and reports `UNVERIFIED` rather than pretending.

## 5. For webhook-speed triggering (optional)

A webhook on the repository pointing at `POST {base}/webhooks/github`, content type
`application/json`, with `GITHUB_WEBHOOK_SECRET` set on both sides. Without a webhook, menD polls
every `MEND_POLL_INTERVAL` (default 30s). See [Webhooks](Webhooks).

## Checking you are ready

```bash
curl -s localhost:8080/actuator/health           # {"status":"UP"}
curl -s localhost:8080/api/repositories | jq '.[] | {slug: (.owner+"/"+.name), accessState, indexState}'
```

`accessState: VALIDATED` and `indexState: INDEXED` on a repository means menD can work on it today.
