# Registering a repository

Registration does three things: it records `owner/name`, it **proves** menD's GitHub identity can
actually do the job there, and it schedules a codebase profile so later issues don't pay to
re-read the repository.

## Register

```bash
curl -s -X POST localhost:8080/api/repositories \
  -H 'content-type: application/json' \
  -d '{"repo":"udaysagarn/superset"}'
```

`POST /api/repositories` — body `{"repo": "owner/name"}`.

Registering an already-registered repository is safe: it re-runs validation instead of failing,
which is exactly how you retry after granting a missing permission.

### 200 OK — the repository record

```json
{
  "id": 1,
  "owner": "udaysagarn",
  "name": "superset",
  "defaultBranch": "master",
  "installationId": "4761586",
  "accessState": "VALIDATED",
  "accessCheckedAt": "2026-08-30T07:22:44.612071638Z",
  "accessError": null,
  "indexState": "INDEXED",
  "indexedSha": "0f1e2d3c4b5a",
  "indexedAt": "2026-08-30T06:36:59.944864Z",
  "indexError": null,
  "contextSessionId": "2b7d4c9a1e5f4c86",
  "contextSessionUrl": "https://app.devin.ai/sessions/2b7d4c9a1e5f4c86",
  "commitsSinceIndex": 0,
  "triggerLabel": "menD:fix",
  "enabled": true,
  "acuBudget": null,
  "createdAt": "2026-08-30T06:33:59.910280Z",
  "updatedAt": "2026-08-30T07:22:44.612071765Z",
  "operational": true
}
```

`200` does **not** mean access is good — it means the verdict was recorded. Read `accessState`.

### 400 Bad Request — malformed slug

```json
{"error":"expected owner/name, got: notaslug"}
```

Owner and name must each match `[A-Za-z0-9_.-]{1,100}`.

## The validation verdict

| `accessState` | Meaning | What to do |
|---|---|---|
| `PENDING` | Registered, not yet validated | Wait, or re-validate |
| `VALIDATED` | Issues, pull requests and labels all reachable | Nothing |
| `NO_ACCESS` | Not visible, archived, or outside the installation's selected repositories | Fix the name, or add the repository to the installation |
| `MISSING_PERMISSION` | Visible, but the installation lacks a permission, or issues are disabled | Grant it and accept the request, then re-validate |

`accessError` is written for a human who can fix it, verbatim — for example:

> The menD GitHub App is installed, but `owner/repo` is not one of its selected repositories. Add it
> under the installation's repository access.

Only `VALIDATED` **and** `enabled` makes a repository `operational: true`. menD ignores webhooks and
polling for everything else.

## Re-validating

```bash
curl -s -X POST localhost:8080/api/repositories/1/validate
```

`POST /api/repositories/{id}/validate` → `200` with the updated repository record, or `404` (empty
body) when no repository has that id. Use it after granting a permission; no need to re-register.

## Listing

```bash
curl -s localhost:8080/api/repositories
```

`GET /api/repositories` → array of repository records, ordered by owner then name.

## The codebase profile

Once a repository is operational, a background reconciler (every `mend.engine.context-interval`,
default 60s) runs one Devin session per repository — one at a time across the whole instance — to
build a **profile** — build and test commands, layout, CI, conventions — and persists it. Later issues get the profile injected into
their prompts instead of every session re-reading the codebase.

| `indexState` | Meaning |
|---|---|
| `NEVER_INDEXED` | No profile yet |
| `INDEXING` | A profile session is running |
| `INDEXED` | Fresh profile, describing commit `indexedSha` |
| `STALE` | Pushes touched something the profile describes; still used, refresh pending |
| `INDEX_FAILED` | See `indexError` |

The profile deliberately includes the repository's own instruction files when present —
`AGENTS.md`, `CLAUDE.md`, `codex.md`, `CONTRIBUTING.md`, `.cursor/rules`, `.agents/skills` — so
Devin honours your conventions rather than menD's guesses.

**Refresh is incremental.** A `push` webhook on the default branch ages only the profile slices
whose files it touched (`commitsSinceIndex` counts up, `indexState` becomes `STALE`), and the
rebuild happens on menD's own schedule — a busy repository cannot drive Devin sessions from webhook
traffic.

## Doing it from the UI

`/repositories/new` has the same flow with step-by-step instructions: the form posts to
`POST /repositories` (form-encoded `repo=owner/name`) and re-renders the page with the verdict or
the error.

## After registering

1. On its first polling pass over the repository, menD ensures its labels exist (`menD:fix`,
   `menD:in-progress`, `menD:pr-open`, `menD:done`, `menD:unverified`, `menD:needs-human`,
   `menD:not-a-candidate`, `menD:changes-requested`).
2. Add the webhook, or rely on polling.
3. Label an issue and watch — see [Triggering issue resolution](Triggering-Issue-Resolution).
