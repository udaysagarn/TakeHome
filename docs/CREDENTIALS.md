# Credentials for menD

menD only runs against real credentials: Devin writes real code and menD writes to a real repository. There is
no offline mode. `./deploy/setup.sh` asks for everything on this page, writes `.env` and starts the stack —
this is the reference for where each value comes from.

You create these yourself; nobody can hand them to you, and none of them belong in the repository. They are
read from the environment at boot and never written to the image, the database or a log line.

Budget for the whole set: about fifteen minutes.

---

## 1. Devin

| Variable | Where it comes from |
|---|---|
| `DEVIN_API_KEY` | https://app.devin.ai/settings/api-keys → **Create key** |
| `DEVIN_ORG_ID` | the `org-…` id in the URL of any page under https://app.devin.ai/org/, or on the API keys page |

Create the key against a **service user**, not your own account: sessions menD creates then show up as the
bot's rather than yours, and revoking it does not lock you out. The key starts with `cog_` or `apk_`.

menD calls exactly four routes with it, all scoped by `DEVIN_ORG_ID`:

```
POST /v3/organizations/{org}/sessions
GET  /v3/organizations/{org}/sessions
GET  /v3/organizations/{org}/sessions/{id}
POST /v3/organizations/{org}/sessions/{id}/messages
```

Sanity check before you go further:

```bash
curl -sf -H "Authorization: Bearer $DEVIN_API_KEY" \
  "https://api.devin.ai/v3/organizations/$DEVIN_ORG_ID/sessions?limit=1" >/dev/null && echo ok
```

Spend is capped per task, not by trust: 3 ACUs for scoping and 10 for remediation
(`MEND_CRITERIA_ACU`, `MEND_REMEDIATION_ACU`).

## 2. The GitHub App

menD acts as an app rather than as a person, so every label, comment and pull request is attributable to the
bot in the audit log, and the token is short-lived and repository-scoped.

1. https://github.com/settings/apps → **New GitHub App**.
2. Name it (for example `mend-bot`), set the homepage to anything, and **uncheck** "Active" under Webhook for
   now — you can turn it on in step 4.
3. Repository permissions — nothing more than these:

   | Permission | Level | Why |
   |---|---|---|
   | Issues | Read and write | read the labelled issue, comment the criteria contract, label the outcome |
   | Pull requests | Read and write | read the remediation PR, comment the verification evidence |
   | Contents | Read | read `AGENTS.md`, `CLAUDE.md`, CI config and the repository profile |
   | Checks | Read | read the check runs that decide `SUCCEEDED` vs `UNVERIFIED` |
   | Metadata | Read | mandatory for any app |
   | Actions | Read and write | *only* if you use the `menD / contract` verification workflow |

4. Subscribe to events: **Issues**, **Push**, **Pull request**, **Pull request review**,
   **Pull request review comment**. (Skip this and menD still works — the 30-second poller covers ingestion,
   which is what a laptop behind NAT needs anyway.)
5. **Create GitHub App**, then note the **App ID** → `GITHUB_APP_ID`.
6. **Generate a private key** — the `.pem` downloads once → `GITHUB_APP_PRIVATE_KEY`. PKCS#1 and PKCS#8 are
   both accepted, as is the path of the `.pem` or the file base64-encoded.
7. **Install App** → pick the repositories you will demo (`your-org/superset`). The installation page URL ends
   in the installation id → `GITHUB_APP_INSTALLATION_ID`:
   `https://github.com/settings/installations/<INSTALLATION_ID>`.

### Webhooks (optional)

Only worth it if menD is reachable from GitHub. On a laptop it usually is not, and the poller makes it
unnecessary. If you do want them: set the webhook URL to `https://<your-host>/webhooks/github`, generate a
random secret, and put it in `GITHUB_WEBHOOK_SECRET` — menD verifies the HMAC-SHA256 signature and rejects
anything that does not match.

```bash
openssl rand -hex 32     # a fine webhook secret
```

### The fallback

`GITHUB_TOKEN=ghp_…` (a classic PAT with `repo`) works instead of the app and is quicker to create, but the
bot then acts as you. Use it to try things out, not for the demo.

## 3. Put them on the machine

```bash
./deploy/setup.sh
```

It prompts for each variable above, accepts the path of the `.pem` for `GITHUB_APP_PRIVATE_KEY` and inlines
its contents, writes `.env`, then builds and starts the stack. Doing it by hand instead — the private key is
multi-line, so paste it from the file rather than by hand:

```bash
{
  echo "DEVIN_API_KEY=cog_…"
  echo "DEVIN_ORG_ID=org-…"
  echo "GITHUB_APP_ID=1234567"
  echo "GITHUB_APP_INSTALLATION_ID=12345678"
  echo "GITHUB_APP_PRIVATE_KEY=\"$(cat ~/Downloads/mend-bot.private-key.pem)\""
  echo "MEND_REPO=your-org/superset"
} >> .env
```

The `echo` matters: `.env` is read literally by docker compose and by Spring, so a line typed as
`GITHUB_APP_PRIVATE_KEY="$(cat key.pem)"` reaches menD as those characters and registration answers
`GITHUB_APP_PRIVATE_KEY does not hold a private key: it still contains an unexpanded shell substitution`. Two
single-line alternatives, if pasting a multi-line value is awkward:

```bash
echo "GITHUB_APP_PRIVATE_KEY=$PWD/mend-bot.private-key.pem" >> .env   # a path, mounted into the container
echo "GITHUB_APP_PRIVATE_KEY=$(base64 -w0 mend-bot.private-key.pem)" >> .env
```

`.env` is in `.gitignore`. Keep it there: it is the one file in this project that must never be committed.

```bash
docker compose up -d --build
open http://localhost:8080
```

## 4. Prove the credentials before the audience arrives

```bash
curl -s localhost:8080/api/repositories | jq '.[] | {owner, name, accessState, accessError, operational}'
```

`accessState: VALIDATED` and `operational: true` means the app is installed with everything menD needs.
`MISSING_PERMISSION` puts the exact permission in `accessError` — menD validates on registration rather than
discovering it halfway through a demo.

Then label one real issue `menD:fix` and watch `/flows`.

## If something is wrong

| Symptom | Cause |
|---|---|
| `GitHub token not configured` in the logs | none of the app variables, and no `GITHUB_TOKEN`, reached the container |
| `accessState: NO_ACCESS` | the app is not installed on that repository, or the installation id belongs to another install |
| `accessState: MISSING_PERMISSION` | permission granted on the app but not accepted on the installation — approve the pending request on the installation page; `accessError` names it |
| Devin sessions never start | key created against the wrong organisation; `DEVIN_ORG_ID` must match the key |
| Nothing is ever dispatched | `MEND_ENGINE_ENABLED` / `MEND_POLLING_ENABLED` are `false` in `.env`; `deploy/setup.sh` writes them `true` |

## Rotation

Both credentials are revocable without touching menD's state: delete the Devin key and create another, or
generate a second private key on the app and remove the old one. Restart the container and the next
installation token is signed with the new key. Nothing in the database depends on either.
