# menD — public API and contracts

menD watches GitHub repositories, decides which issues are safely automatable, has Devin fix them,
and refuses to call anything a success unless something other than the session that wrote the code
says so.

This wiki is the contract surface: every route, payload and status code on these pages was read out
of the controllers and exercised against a running instance, so nothing here is aspirational.

### Start here

| If you want to | Read |
|---|---|
| Know what you need before anything works | [Prerequisites](Prerequisites) |
| Connect a repository to menD | [Registering a repository](Registering-a-Repository) |
| Make menD fix an issue | [Triggering issue resolution](Triggering-Issue-Resolution) |
| Find out where an issue got to | [Polling task status](Polling-Task-Status) |
| Understand what a state means | [Issue lifecycle and states](Issue-Lifecycle-and-States) |
| See the contract Devin is held to | [Success criteria contract](Success-Criteria-Contract) |
| Know why a fix is (or is not) trusted | [Verification and evidence](Verification-and-Evidence) |
| Wire GitHub up to menD | [Webhooks](Webhooks) |
| Read what reviewers have taught menD | [Learnings API](Learnings-API) |
| Pull numbers for a review | [Reports and metrics](Reports-and-Metrics) |
| Configure a deployment | [Configuration reference](Configuration-Reference) |
| Handle failures | [Errors and limits](Errors-and-Limits) |
| See every route on one page | [API index](API-Index) |

### The shape of the thing

- **Base URL** — wherever you run menD. Locally that is `http://localhost:8080`.
- **JSON API** — everything under `/api`. No authentication is enforced by menD itself; see
  [Errors and limits](Errors-and-Limits#authentication-and-exposure).
- **Web UI** — `/` (overview), `/flows` (board), `/tasks/{id}`, `/repositories/new`,
  `/learnings`, `/deck`. Human surfaces, not contracts; they can change shape without notice.
- **Webhooks** — `POST /webhooks/github`, the low-latency trigger.
- **Operations** — `/actuator/health`, `/actuator/info`, `/actuator/metrics`,
  `/actuator/prometheus`.

### One thing to understand before reading anything else

menD is not a request/response fixer. Every write endpoint is a *trigger*: it records intent and
returns immediately, and a reconciler drives the work forward on its own schedule. `202 Accepted`
means "durably queued", never "done". You find out what happened by
[polling the task](Polling-Task-Status), by watching the labels on the issue, or by waiting for the
pull request.
