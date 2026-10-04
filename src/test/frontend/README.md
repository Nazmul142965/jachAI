# Frontend journey checks (mocked fixtures, real browser)

Reproducible browser checks for the JachAI dashboard. They serve the **real** `index.html`,
`app.js` and `app.css` and drive them in a real browser engine, while `/api/v1` is answered by
fixtures whose field names come from the real Java records.

**What this is not:** it is not a live backend walkthrough. No Spring Boot, no MongoDB, no Redis, no
SMTP, no AI provider and no payment gateway are involved. A green run here means the frontend
renders, guards and recovers correctly against the documented contract — nothing more. Live
verification is a separate, still-pending gate (see the bottom of this file).

## Run

```bash
node src/test/frontend/run-journeys.js
```

- No dependencies; Node 22+ (uses the built-in `fetch` and `WebSocket`).
- Browser: auto-detects Chrome or Edge. Override with `CHROME_PATH=/path/to/browser`.
- Evidence: `target/frontend-evidence/journeys.json` (every check with its result and detail).
  Override the directory with `EVIDENCE_DIR`.
- Exit code: `0` all checks pass, `1` at least one failed, `2` no browser could be launched.

Nothing is booted: `static-server.js` serves `src/main/resources/{templates,static}` exactly as the
page controller would, so a fixture run costs seconds instead of a Spring start-up.

## Setup

| Piece | Detail |
| --- | --- |
| `static-server.js` | serves the real templates and static assets on a loopback port |
| `fixtures.js` | `installScript({ role, storage })` returns a script injected **before** `app.js` that replaces `window.fetch` for `/api/v1` |
| `run-journeys.js` | launches headless Chrome, drives the UI over the DevTools Protocol, asserts and records evidence |
| Roles | `learner`, `academy-staff`, `talent-recruiter`, `platform-admin` |
| Storage variants | `ok`, `warn` (above threshold), `unavailable` (503), `forbidden` (403) |

Every request the page makes is recorded in `window.__calls`, which the checks use to prove that a
UI action really issued its API call (for example that starting an attempt posts nothing before the
instructions are accepted).

## Journeys and expected results

| Journey | Scenarios | Expected results |
| --- | --- | --- |
| `learner` | sign-in → My assessments → Start/resume → Begin → answer → expiry → submit → result | learner-scoped nav only; instructions appear **before** any attempt is created; server deadline shown as `Time remaining m:ss`; every answer starts *Not saved* and becomes *Saved* after the PUT; expiry announces "Time is up", disables saving and keeps submit available; submit opens an `aria-modal` dialog that counts unsaved answers; an unreleased result shows no score and offers *Check again* |
| `academy-staff` | Members → role change → Assessments → release → staff review → grade → certificate issue/revoke | member rows render with a page filter; the role dialog states the transition in plain words; the release dialog names the attempt and score and says the learner is emailed, and the confirmation claims a **queued** mail, not delivery; staff review exposes the answer key; grading confirms it is final and audited; issuing shows the code and public path and does not claim delivery; revoke confirms it is terminal and reports the code as invalid |
| `talent-recruiter` | Hiring campaigns → Compare candidates → Integrity signals → Event log | campaign list renders; the integrity summary carries its caveat; the paged raw event log is exposed and warns the signals are not proof of misconduct; a review decision control is offered |
| `platform-admin` | Platform → operations → storage → organizations → user lookup → role dialog | operations render with a timestamp; the storage panel is labelled an estimate, shows the size breakdown and the threshold source, and states it is **not** Atlas's official quota; organizations carry owner and creation date; the role dialog names the person (name, email, id) and the from→to transition |
| `platform-admin` (`warn`) | storage above threshold | the warning is textual, not colour-only, and advises review rather than claiming a quota breach |
| `platform-admin` (`unavailable`) | storage 503 | the server message is shown verbatim and a *Retry* control appears |
| `mobile` | Members at 320px and 375px | no horizontal overflow, sidebar nav becomes a grid, small action buttons measure at least 44px |

## Keeping fixtures honest

`fixtures.js` mirrors the Java records (`AttemptView`, `StaffAttemptReviewView`, `ResultView`,
`AssignmentView`, `Platform*View`, `UsageReconciliationReport`, `TelemetryEventView`). If a backend field
is renamed, update the fixture **and** the check: a fixture that no longer matches the contract turns
a real regression into a green run.

## Learner API gaps closed in main

`GET .../assignments/mine` now returns `AssignmentView` with `assessmentTitle`,
`durationMinutes`, and `questionCount`; `GET .../assignments/{id}` retrieves only the signed-in
learner's or candidate's own assignment. Neither route exposes questions or answer keys.
`ResultView.state` now distinguishes `GRADING_PENDING`, `RELEASE_PENDING`, and `RELEASED` without
revealing a score before release. The fixture journey checks that the learner screen uses those fields.

## Live gate (pending)

Live checks must not run until a disposable `MONGODB_TEST_URI` is supplied through the environment
and its database name begins with `jachai_it_`. The backend refuses a URI without that prefix, and
fixture runs must never be described as live verification. When the gate opens:

```bash
$env:JAVA_HOME='<jdk21>'; .\mvnw.cmd -o test -Dtest=TenantScopedReadIntegrationTests
```

For a separate **local browser/demo** check (not Atlas integration and not a
fixture run), first start a populated loopback JachAI demo. Set
`DEMO_BASE_URL`, `DEMO_EMAIL` (an Academy learner with a submitted batch
attempt and released result), `DEMO_CANDIDATE_EMAIL` (a Talent campaign
candidate with an assignment), `DEMO_OWNER_EMAIL` (the Academy/Talent owner), and
`DEMO_PASSWORD` privately in the shell. Then run
`node src/test/frontend/run-live-local.js`. It refuses non-loopback URLs,
is read-only by default, signs in through the real page, and saves
light/dark screenshots under ignored `target/frontend-live-evidence/`.
It checks the Academy learner result/cohort, Talent recruiter comparison /
candidate assignment, and the owner's read-only Billing view (plan, quota
reconciliation, order history, sandbox disclosure, and checkout button state).
For the disposable `JachAI Demo Talent` candidate only, setting
`DEMO_ALLOW_REVIEW_WRITE=1` also saves a HOLD decision with a fixed demo note
and checks that the decision and immutable audit history render. It refuses
other workspaces, candidates, and non-demo review history before writing.
It never starts checkout or repairs counters. It does **not** prove every action
in those roles, payment-provider callbacks, Redis, external providers, or
deployment.

Remaining live-only unknowns: real authentication against MongoDB, Redis-backed rate limits and
revocation, SMTP/outbox delivery, AI providers, and both sandbox payment gateways.
