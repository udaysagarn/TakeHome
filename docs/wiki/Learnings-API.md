# Learnings API

When a human reviews a menD pull request, the feedback is not thrown away. A retrospective session
turns it into lessons, each stored with the review that caused it, and active lessons are injected
into later sessions for that repository.

## Read them

```bash
curl -s localhost:8080/api/learnings
```

`GET /api/learnings` → three lists:

| Key | Contains |
|---|---|
| `active` | Everything currently injected into prompts, highest confidence first |
| `recommendedActions` | Active lessons whose recommended action needs a **human** to perform it |
| `retired` | Kept for the audit trail, never injected |

```json
{
  "id": 2,
  "scope": "GENERAL",
  "repo": null,
  "topic": "lockfiles",
  "lesson": "When a lockfile changes, show the resolved version diff in the pull request body.",
  "evidence": "Reviewer asked twice which transitive version was pinned.",
  "recommendedAction": "DEVIN_KNOWLEDGE",
  "actionDetail": "Worth promoting to an organisation-wide Devin knowledge note.",
  "status": "ACTIVE",
  "fingerprint": "14cbf8fc1854bba65cb36697a0e95399",
  "sourceRepo": "udaysagarn/superset",
  "sourceIssue": 104,
  "sourcePrUrl": "https://github.com/udaysagarn/superset/pull/9003",
  "timesApplied": 3,
  "timesFollowedByFeedback": 1,
  "confidence": 0.74,
  "createdAt": "2026-08-30T06:35:09.988023Z",
  "updatedAt": "2026-08-30T07:22:42.037240Z",
  "lastAppliedAt": "2026-08-30T07:21:52.154219Z",
  "retiredAt": null
}
```

Every lesson carries its provenance — `sourceRepo`, `sourceIssue`, `sourcePrUrl`, `evidence` — so a
skeptical engineer can go read the review that produced it.

## Scope

| Scope | Meaning |
|---|---|
| `REPO` | About one codebase: its conventions, its reviewers, its test layout. Injected into that repository's sessions only |
| `GENERAL` | True beyond this repository, so it is worth pushing further than menD's own prompts |

## Recommended actions

| Action | Who performs it |
|---|---|
| `PROMPT_PREAMBLE` | menD, automatically — injected into that repository's scoping and remediation prompts |
| `RETIRE` | menD, automatically |
| `DEVIN_KNOWLEDGE` | **A human.** Promote to a Devin knowledge note (or playbook) so every session in the org benefits, not just menD's |
| `REPO_INSTRUCTIONS` | **A human.** Add it to the repository's own `AGENTS.md` / `CLAUDE.md` / `CONTRIBUTING.md` |
| `MEND_BACKLOG` | **A human.** The lesson is about menD itself, e.g. the candidacy gate accepting the wrong kind of issue |

The last three appear in `recommendedActions` and on `/learnings`. menD deliberately does not perform
them: they change artefacts it does not own. Promotion is a roadmap item, and when it lands it will
be behind a named human approval with an audit trail.

## Earning its place

| Field | Meaning |
|---|---|
| `timesApplied` | Sessions that ran with this lesson injected |
| `timesFollowedByFeedback` | Of those, how many still drew reviewer feedback |
| `confidence` | Adjusted as that ratio moves |

A lesson that keeps being followed by feedback is retired after
`mend.learning.min-applications-before-retiring` (default 4) applications, so the prompt does not
grow without bound. A retired lesson that a later review teaches again is reinstated with its
counters reset — capped at two retirement cycles, after which it stays retired. A lesson retired
while it was still awaiting human promotion is flagged as such rather than disappearing quietly.

At most `mend.learning.max-lessons-in-prompt` (default 12) lessons ride along with any one session.

## Not an API yet

There is no public write route for learnings: no create, no approve, no retire over HTTP. Reviewers
teach menD through GitHub, and everything else is read-only. `/learnings` (HTML) is the human view.
