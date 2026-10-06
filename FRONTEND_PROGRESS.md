# Frontend progress — current main and historical dashboard branch

The table below records an earlier `frontend/dashboard` milestone. It is not a
current backlog for `main`. On `main`, the three in-app workspaces, Platform
manual-plan controls, version history, tenant usage reconciliation, order
detail, account settings, personal practice, role-specific home, and local-only
demo login are already implemented. Live-service release gates remain open.

## Done on this milestone

| Commit | Delivered |
| --- | --- |
| `e3f57b3` | Grouped product-aware navigation, workspace header with product + role, landing certificate-verification entry point and invitation hint, tabpanels, role-aware overview quick actions |
| `946a69d` | Assignment window states (open/opening/closed) with Start disabled when closed, sticky attempt header with timer + saved-answer counter, released-result timestamps and per-topic score bars, certificates shortcut, sticky sidebar |
| `f74faa2` | At-a-glance staff metrics (waiting for release, questions awaiting approval, draft assessments, campaigns, members) as navigable cards with honest failure copy |
| `d4446f0` | Talent campaign rows (open/closed, frozen version, created date) and an "At a glance" comparison table |
| _this slice_ | Public `academy.html` and `talent.html`, redesigned public `certificate.html`, `PageController` + `SecurityConfig` entries for the two pages, landing links |

## Historical checklist — resolved on main

The instructions below are archived from an old branch. Do not implement them
again; inspect the current files before opening new work.

1. **New in-app screens (not extra cards).** The three distinct views now exist in the shell:
   (token stays in JS memory; do not create separate authenticated pages):
   - `academy-batch` — Academy staff batch/cohort workspace: pick course → batch, see batch
     assessments, per-learner progress rows, cohort analytics and the reconcile action on one
     screen, with the batch selector in the page head. New nav entry under **Academy**, opened from
     the Courses & batches list ("Open workspace") and from a metric card.
   - `talent-campaign` — Talent campaign workspace: one campaign's frozen version, candidate
     comparison, integrity summary + paged event log, review decision form and review history, with
     the campaign title in the page head and a back link. Nav entry under **Talent**, opened from the
     campaign row.
   - `assessment-detail` — Learner/candidate assessment detail: assignment window, attempts allowed,
     attempt history, current attempt state, released result with topic bars and feedback/AI tutor
     entry points, opened from the assignment row instead of inline panels.
   Implementation notes: add each id to the `views` array, give `showView` a case that reads a
   selected-id from state, keep the existing list views as entry points, and extend
   `src/test/frontend` fixtures only where a check needs a new payload.
2. **Platform manual TEAM controls** are implemented (API and UI):
   - `GET /api/v1/platform/organizations/{orgId}/billing/manual-plan` → `ManualPlanView(organizationId, active, latestAction, expiresAt, revision, changedAt)`
   - `POST .../manual-plan` → `ManualPlanRequest(expiresAt, reason)` (reason 10–500 chars)
   - `POST .../manual-plan/revoke` → `RevokeManualPlanRequest(reason)`
   - `GET .../manual-plan/audit?page=0` → `Page<ManualPlanAuditView>`
   Add a panel in the Platform view with explicit **"manual grant — not a payment"** wording, the
   expiry and reason captured before the call, revoke with its own reason, and the paged audit.
   Never imply a paid upgrade: no browser-only activation, no payment-success language.
3. **Cohesion and states pass (historical)** wired `fieldErrors` into staff forms
   (question, assessment, campaign, invite, feedback), add empty/error states to the secondary lists
   that still fetch silently (invitations, audits, messages), verify focus order and contrast at
   320/375/tablet/desktop, and check no view overflows horizontally.
4. **Verification per role at the milestone.** Browser fixture walkthroughs per role (learner, academy
   staff, recruiter, platform admin) using fixtures — labelled as fixtures — plus `node --check` and
   a full `mvnw -o test` at the end. Do not boot Spring for static fixture checks; use
   `node src/test/frontend/run-journeys.js --screens`. Fixture coverage has
   since grown beyond this milestone; verify its current result, not this old list.

## Remaining release gates (current)

- Real authenticated browser journeys against the Spring Boot API and a
  dedicated Atlas test database; static fixtures do not prove persistence.
- Live SMTP, Google, Gemini/Groq, Redis and both payment sandboxes.
- Desktop/mobile visual review of the signed-in home, practice, account,
  Academy, Talent and Platform flows; keyboard and reduced-motion review.
- The current product/API gap list must be regenerated from source before
  assigning more frontend endpoint work. The former three missing endpoints
  are not missing now.

## Documented API gaps resolved in main

| Endpoint | Problem |
| --- | --- |
| `GET …/assignments/mine` | Resolved by `AssignmentView` with assessment title, duration and question count; own-assignment detail is available at `GET …/assignments/{id}`. |
| `GET …/attempts/{id}/result` | Resolved by `ResultView.state`: `GRADING_PENDING`, `RELEASE_PENDING`, `RELEASED`. |

## Live gate (pending)

No `MONGODB_TEST_URI` in the environment, so MongoDB/Redis/SMTP/AI/payment journeys remain
unverified. Fixture runs must never be described as live verification.
