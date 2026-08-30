# Success criteria contract

Before menD spends anything on a fix, it insists on a machine-checkable definition of done. That
contract is produced once, hashed, stored, injected into the remediation prompt, and checked at the
end. If no such contract can be written, the issue is excluded — that exclusion is a feature.

## The schema

Produced as Devin structured output, or authored by a human in the issue body. JSON Schema draft-7,
`additionalProperties: false`, every field required.

| Field | Type | Meaning |
|---|---|---|
| `is_candidate` | boolean | True only if the issue can be fixed **and** objectively verified without further human input |
| `confidence` | number 0–1 | Gate compares against `mend.triage.min-confidence` (default 0.7) |
| `problem_restatement` | string | The problem, in menD's words — catches misreadings early |
| `acceptance_criteria` | string[] | Objectively checkable statements that must all hold |
| `verification_commands` | string[] | Commands runnable in the repository that prove those criteria |
| `files_in_scope` | string[] | Where the change is expected to land |
| `test_plan` | string | Which automated test proves the fix — the file and case to add or change, following the repository's conventions. "No test change, and here is why" is an allowed answer that must name the existing check that covers it |
| `risk` | `low` \| `medium` \| `high` | |
| `blocking_unknowns` | string[] | Questions only a human can answer. Non-empty ⇒ not a candidate |
| `rationale` | string | Why this is (or is not) automatable |

## A real one

```json
{
  "is_candidate": true,
  "confidence": 0.86,
  "problem_restatement": "A bounded change with an objectively checkable definition of done.",
  "acceptance_criteria": [
    "The reported defect no longer reproduces",
    "No existing test is weakened, skipped or deleted"
  ],
  "verification_commands": ["npm test -- --run", "npm audit --audit-level=high"],
  "files_in_scope": ["package-lock.json"],
  "test_plan": "Extend the existing suite with a case that fails before the change and passes after.",
  "risk": "low",
  "blocking_unknowns": [],
  "rationale": "The definition of done is stated in the issue and is machine-checkable."
}
```

Read it back on any task: `GET /api/tasks/{id}` → `criteria`, plus `criteriaJson` (exactly what was
stored) and `criteriaHash` (e.g. `b7d5bee864f20b14`), which is what lets you prove the contract did
not change mid-flight.

## The gate

An issue becomes `NOT_A_CANDIDATE` — with the reasons commented on the issue — when any of these
hold:

- `is_candidate` is false
- `confidence` < `mend.triage.min-confidence`
- `acceptance_criteria` is empty — no definition of done to verify
- `verification_commands` is empty — no way to prove a fix
- `blocking_unknowns` is non-empty — a human has to answer something first

Before any of that, a free deterministic pre-filter rejects issues with a body under 60 characters,
no title, a denylisted label (`question`, `discussion`, `epic`, `wontfix`, `invalid`), or an unfilled
template — no ACU spent.

## Writing the contract yourself

Put a fenced `devin-criteria` block in the issue body and menD skips the scoping session entirely:

````markdown
```devin-criteria
{"is_candidate": true, "confidence": 0.9, "problem_restatement": "…",
 "acceptance_criteria": ["…"], "verification_commands": ["pytest tests/unit -q"],
 "files_in_scope": ["superset/…"], "test_plan": "…", "risk": "low",
 "blocking_unknowns": [], "rationale": "…"}
```
````

An unparseable block is ignored (logged as a warning) and menD falls back to a scoping session, so a
typo costs you latency, not correctness.

## How the contract is used afterwards

- The remediation prompt carries it verbatim, plus the repository profile and the active
  [learnings](Learnings-API), and forbids weakening or deleting existing tests.
- `verification_commands` are exactly what the
  [verification tiers](Verification-and-Evidence) run — the same commands the contract promised, not
  a re-derived set.
- The remediation session reports per-criterion evidence, which menD posts on the issue.
- When a human reviewer disagrees with the contract, the reviewer wins: their comments are handed
  back to the session that wrote the code.
