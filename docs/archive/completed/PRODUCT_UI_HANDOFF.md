# JachAI product UI handoff

This file records the current frontend slice for another agent. Read
`ARCHITECTURE.md` for authorization/data rules and `FRONTEND.md` for the
no-build HTML/CSS/vanilla-JS conventions. Do not infer a backend feature from
an exposed button: only the server can grant access or enforce a quota.

## Worktree and ownership

- Branch: `frontend/product-home`; worktree: `E:\Advance Java\jachAI-product-ui`.
- Branched from `main` at `e34ebd2`. The original `jachAI` worktree is not
  edited by this slice. Merge/rebase only after reviewing concurrent backend
  work. Do not force-reset either branch.
- This slice touches `templates/index.html`, `static/app.js`, `static/app.css`,
  and frontend documentation. It does not change Java or API contracts.

## Product decision

One account can use personal B2C practice, participate in an Academy or
Talent workspace, and own another workspace. It must not be forced to create
an organization to learn. Personal AI practice is never evidence for an
official score or certificate. An organizer's work remains tenant-scoped.

The new account home is a stable entry point for all users. For a participant,
it points to assigned assessments when the first page has assignments;
otherwise it suggests a recently missed practice topic when the matching
question is still in the question list. Staff next actions are based on the
current workspace's pending review count, Academy draft questions, or a Talent
workspace with no campaign; when none applies, they go to the existing
workspace overview. A missing API response is shown as unavailable, not zero.

The Practice screen shows a searchable question rail beside the active
question. It marks only questions found in the first page of recent answers as
`Answered recently`; it deliberately does not claim all other questions are
unanswered. Generation has visible busy/success/failure states. A successful
POST displays the returned question immediately, even if the subsequent list
or usage refresh fails. Unknown usage disables further generation until the
user reopens Practice, rather than guessing the remaining allowance.
On first open, the first returned question is selected automatically; the
large empty fieldset prompt was removed. If the question list really cannot
load, the answer form is hidden and a small retry panel is shown instead.
The desktop workspace background continues the sidebar surface through the
full page height, and Practice has less trailing space below its panels.

## Existing API dependencies and limits

- `GET /practice/questions` returns a flat list of up to 100 generated
  questions followed by five starter questions. The view has no reliable
  starter/generated flag, created date, or pagination metadata.
- `GET /practice/usage` supplies today’s generation and answer allowance.
- `GET /practice/answers/mine?page=0` supplies just the first 20 answers.
  The home and question rail make no lifetime-statistic or mastery claim.
- `GET /organizations/{id}/assignments/mine?page=0` is called only for an
  active Academy learner or Talent candidate; the home says only that
  assignments exist and sends the user to My Assessments for the real window
  and attempt state.
- Staff home recommendations use tenant-scoped `GET /attempts/review/pending`,
  Academy `GET /questions?status=DRAFT`, or Talent `GET /talent/campaigns`.
  The existing Overview remains the detailed staff work queue. If a request
  fails, the home does not mistake it for a zero count.

## Research-informed next slices, not implemented here

1. Curated skill/topic paths and a short baseline diagnostic before AI
   generation. Current free-text topic input is not a curriculum.
2. Practice sessions with a question count and recap. This needs a server
   session model and explicit per-question quota accounting; do not assemble
   a browser-only pseudo-assessment.
3. Topic trends and `practice next` recommendations across more than the
   first 20 answers. Add a server summary API with bounded aggregation and
   defined retention semantics before displaying a mastery score.
4. Academy learning-path progress and Talent invitation funnel. Reuse
   existing tenant analytics only where the field semantics match. Do not mix
   personal practice history into tenant reports without explicit consent.
5. Review the final UI at desktop, 375px, and 320px in both themes. Check
   keyboard focus and `prefers-reduced-motion` for the loading indicator.

Official product references used to choose these seams:

- CodeSignal separates individual learning paths from organization-level
  skills, usage, and path analytics:
  <https://support.codesignal.com/hc/en-us/articles/28639182832919-How-can-I-use-CodeSignal-Learn-for-Organizations-Analytics>
- HackerRank connects practice, mock tests, feedback and certification in
  role-specific prep kits:
  <https://help.hackerrank.com/articles/1723224478-introduction-to-prep-kits>
- TestGorilla treats the hiring funnel and candidate skill breakdown as
  employer tasks:
  <https://support.testgorilla.com/hc/en-us/articles/43407991450779-Analyze-your-candidates-results>
- Khan Academy uses practice results to guide the learner’s next work, rather
  than showing only a score:
  <https://support.khanacademy.org/hc/en-us/articles/360007253831-Using-self-paced-practice-and-Mastery-in-the-classroom>

## Verification

Checks performed for this slice:

- `node --check src/main/resources/static/app.js` passed.
- `git diff --check` passed.
- `node src/test/frontend/run-journeys.js` was attempted, but the local
  headless browser never reported a journey result after starting the static
  server. A second focused `--no-workspace-only` run behaved the same way.
  Both runs were interrupted. Treat browser verification as **pending**, not
  passing. Investigate the local Chrome/DevTools launch separately rather
  than repeatedly rerunning the entire suite. The no-workspace and learner
  fixture assertions were updated for the new dashboard and question rail.

The normal commands for a later verifier are:

```text
node --check src/main/resources/static/app.js
node src/test/frontend/run-journeys.js
```

The browser fixture runner uses a static server and mocked API responses;
passing it is not proof that MongoDB, SMTP, an AI provider or payments work.
Record actual results in the commit message/handoff after the run. A live
authenticated browser pass is still required before production.

## Account and practice follow-up (2026-10-06)

- `GET /api/v1/auth/me` returns email, display name, verification and sign-in
  method flags, and account creation time; never returns password hashes.
- `PATCH /api/v1/auth/me` changes only the display name.
- `POST /api/v1/auth/change-password` requires the current password, changes
  the hash, increments `securityVersion`, and revokes refresh sessions. The UI
  signs out after success. Google-only accounts use the password-reset link.
- The Account screen is available even without an organization. It shows the
  personal free practice allowance and, for organization admins, the workspace
  tier. Personal paid plans are not implemented; the UI does not offer payment.
- Practice shows both used and remaining daily allowances. A provider failure
  is **not** evidence that the user used their allowance or that the provider
  exhausted its quota. Without the provider response/log, distinguish neither.
- The first practice question is selected automatically; a retry panel is
  shown only when no questions can be loaded. The sidebar background continues
  to the bottom of the desktop workspace, and generation has a visible active
  state without claiming an estimated completion time.
- Verification for this follow-up: `AuthServiceTests` passed with Java 21;
  `node --check src/main/resources/static/app.js` passed; the focused
  `--no-workspace-only` browser fixture passed 12/12 checks. Live SMTP, MongoDB,
  and AI provider behavior were not tested in this slice.

## Role-specific home work list (2026-10-06)

The signed-in home now uses the same tenant-scoped reads already used by its
recommended action. Learners and candidates see up to three real assignments,
each with an availability state and a link to its assessment detail. Academy
staff see pending review items and draft questions; Talent staff see pending
reviews and campaigns. A personal account without a workspace sees a direct
practice entry. Failed reads say the work cannot be loaded, rather than showing
an invented zero. The broad explore actions remain secondary.

This is a browser-facing improvement, not a new billing or assignment rule.
The static fixture is not evidence of live MongoDB or AI behavior.

## AI failure meaning (2026-10-06)

`SpringAiService` now maps known provider HTTP failures to separate safe error
codes: rate limit (429), provider account billing (402), key/access (401/403),
model/request configuration (400/404), and timeout/5xx. Logs include provider,
use case, category and exception class only—not the provider response text or
API key. Unknown failures remain `AI_UNAVAILABLE`. No provider-specific quota
balance is inferred from these statuses. Practice keeps its personal 20/day
generation counter separate from the platform-wide safety cap; a failed
generation refunds both reservations. The UI reports which boundary stopped
the request and continues showing the personal used/remaining allowance.
Focused unit tests cover 429, 401 and the platform cap; live Gemini/Groq calls
are still unverified. Official status semantics:
<https://ai.google.dev/gemini-api/docs/troubleshooting> and
<https://console.groq.com/docs/errors>.

The same pass found and fixed an API/UI field mismatch: `PracticeService.Usage`
returns `limit` for answer checks, not `dailyLimit`. Both the Practice usage
sentence and Account usage progress now read `limit`. The focused learner
browser journey passed 48/48 checks against a fixture shaped like that record.

## Workspace-switch response isolation (2026-10-06)

Account-home reads are asynchronous. A load token now invalidates pending home
responses on workspace switch, sign-out, or a newer home load. The renderer
also checks the original access token, organization ID and active view before
using any settled result. A focused browser fixture delayed an Academy learner
assignment response, switched to a new Academy owner workspace, and confirmed
the old assignment could not overwrite the new home. The backend still owns
tenant authorization; this is an additional UI correctness boundary.
