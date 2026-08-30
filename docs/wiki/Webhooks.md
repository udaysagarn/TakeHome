# Webhooks

`POST /webhooks/github` is menD's low-latency trigger. It is optional — the poller finds labelled
issues within `MEND_POLL_INTERVAL` (default 30s) — but without it there is no push-based review
feedback or profile ageing.

## Setting it up

On the repository (or the GitHub App), add a webhook:

| Setting | Value |
|---|---|
| Payload URL | `https://your-mend-host/webhooks/github` |
| Content type | `application/json` |
| Secret | The same value as `GITHUB_WEBHOOK_SECRET` |
| Events | Issues, Pushes, Pull requests, Pull request reviews, Pull request review comments |

## Signature verification

`X-Hub-Signature-256` is verified as `sha256=` + HMAC-SHA256 of the raw body, compared in constant
time. A bad or missing signature → `401 Unauthorized` with body `invalid signature`.

If `GITHUB_WEBHOOK_SECRET` is unset, verification is skipped — local and demo mode only. Never run
an internet-reachable instance without it.

## Events menD acts on

| `X-GitHub-Event` | Condition | Effect |
|---|---|---|
| `issues` | `action: labeled` and the label equals the repository's trigger label | Ingests the issue and queues remediation |
| `push` | Ref equals the repository's default branch | Ages the profile slices whose files changed |
| `pull_request` | `action: closed` and `merged: false` | Treated as a review verdict: the least ambiguous one there is |
| `pull_request_review` | Any | Feeds the review loop |
| `pull_request_review_comment` | Any | Feeds the review loop |

Everything else is accepted and ignored.

## Responses

All responses are plain text. Every one of them is `2xx` unless the signature failed or menD threw —
GitHub should not retry a delivery menD deliberately ignored.

| Status | Body | When |
|---|---|---|
| `202 Accepted` | `queued udaysagarn/superset#108` | Trigger label on a registered repository |
| `202 Accepted` | `noted 3 commit(s), 12 changed path(s) on udaysagarn/superset` | Push to the default branch |
| `202 Accepted` | The review loop's own verdict text | Review event on a pull request menD owns |
| `200 OK` | `ignored: ping` | Unsupported event type |
| `200 OK` | `ignored: unknown/repo is not a registered repository` | Repository not registered or not operational |
| `200 OK` | `ignored: labeled/bug` | Wrong label or wrong action |
| `202 Accepted` | `ignored: push to refs/heads/feature-x` | Push to a non-default branch |
| `202 Accepted` | `ignored: no pull request in the payload` | Review event menD cannot attach to a pull request |
| `401 Unauthorized` | `invalid signature` | HMAC mismatch |
| `500` | `error` | menD failed to handle the payload; the exception is logged |

## Trying it locally

```bash
curl -i -X POST localhost:8080/webhooks/github \
  -H 'X-GitHub-Event: issues' -H 'content-type: application/json' \
  -d '{"action":"labeled","label":{"name":"menD:fix"},
       "repository":{"full_name":"udaysagarn/superset"},
       "issue":{"number":108,"title":"…","body":"…"}}'
```

## Design notes worth knowing

- **Webhooks never drive Devin sessions directly.** A push marks the profile stale; the rebuild
  happens on menD's own schedule, so a busy repository cannot turn webhook traffic into ACU spend.
- **Deliveries are idempotent.** A task is unique by `(repo, issueNumber)`, so a redelivery resolves
  to the same row rather than starting a second attempt.
- **Review feedback is a state, not a failure.** `CHANGES_REQUESTED` hands the reviewer's comments
  back to the session that wrote the code (it still has the context), bounded by
  `mend.learning.max-review-rounds` (default 3) before escalating to a human.
