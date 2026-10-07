# React signed-in dashboard — delivery

Worktree `E:\Advance Java\jachAI-react-ui`, branch `frontend/react-dashboard`,
branched from `main` at `66ef519`. Neither the main folder nor any other
worktree is touched by this branch.

Note on where `main` was: the main folder is currently checked out on the
`support-center` branch (`48c948e`), one commit ahead of `main`. This branch is
cut from `main`, not from `support-center`, so the support-center work and this
work stay independent.

## Port and database used here

Taken from the current configuration rather than assumed. `application.properties`
sets no `server.port`, so Spring's default is 8080; `scripts/start-local.ps1`
overrides that to 8093 with `-Demo` and takes the database from
`DEMO_MONGODB_DATABASE` (default `jachai_demo_local`).

To avoid colliding with any running instance, everything here is tested with both
values passed explicitly:

```
scripts/start-local.ps1 -Demo -Port 8102 -DatabaseName jachai_demo_react
```

Nothing in this branch is tested against 8093 or 8095.

---

# Phase 1 — inventory of the current signed-in UI

Read from source, not from an earlier description. Current implementation is
`templates/index.html` (399 lines), `static/app.js` (4787 lines) and
`static/app.css` (1381 lines), with no build step.

## Roles and workspace types

Five workspace roles, set per organization, plus one cross-cutting flag:

| Role | Workspace type | What they are |
| --- | --- | --- |
| `ORG_ADMIN` | Academy or Talent | Owner/administrator of a workspace |
| `INSTRUCTOR` | Academy | Authors and grades, runs cohorts |
| `LEARNER` | Academy | Takes assigned assessments, practises |
| `RECRUITER` | Talent | Runs hiring campaigns |
| `CANDIDATE` | Talent | Takes an assessment by invitation |
| *platform admin* | — | A flag on the account, not a workspace role; unlocks Platform Operations |

One account can hold different roles in different workspaces, and personal
practice exists outside every workspace. That is the single most important fact
about the information architecture: **the account and the workspace are two
different scopes**, and the current sidebar already separates them.

## Screens and views

15 screens containing 22 views. The sidebar lists screens; a screen with more
than one view shows a tab strip. Routing is hash-based, `#/screen/tab`.

| Screen | Views (tabs) | Shown to |
| --- | --- | --- |
| `start` — My dashboard | start | everyone (account scope) |
| `practice` — Practice | practice | everyone (account scope) |
| `account` — Profile & settings | account | everyone (account scope) |
| `workspace-access` — Your workspaces | workspace-access | everyone (account scope) |
| `overview` — Overview | overview | everyone, per workspace |
| `assessment-studio` — Assessment Studio | questions, assessments, assign, review | staff (ORG_ADMIN, INSTRUCTOR, RECRUITER) |
| `members` — Members | members | ORG_ADMIN |
| `academy` — Academy Workspace / My batches | academy, academy-batch | ORG_ADMIN, INSTRUCTOR, LEARNER (learner sees only the first tab) |
| `messages` — Messages | messages | INSTRUCTOR, LEARNER |
| `certificates` — Certificates | certificates | LEARNER |
| `talent` — Talent Campaigns | campaigns, talent-campaign | Talent staff |
| `my-assessments` — My Assessments | attempts, assessment-detail | LEARNER, CANDIDATE |
| `billing` — Organization Billing | billing | ORG_ADMIN |
| `platform` — Platform Operations | platform, platform-tenants, platform-admins | platform admins |

Sidebar grouping, which the React version should keep because it carries real
meaning: **My account** / **Workspace** / **Assessment engine** / **Academy** or
**Talent** / **My work** / **Administration**.

Landing screen by role: participants (`LEARNER`, `CANDIDATE`) land on `start`;
everyone else lands on `overview`. A hash route is honoured only if the role
actually has that screen, otherwise it silently falls back — behaviour worth
preserving.

## API surface

116 distinct paths. Grouped by owner, with counts:

| Area | Paths | Notes |
| --- | --- | --- |
| `auth` | 10 | register, login, refresh, logout, me, change-password, Google link/config, email delivery mode |
| `practice` | 5 | generate, questions, answers, answers/mine, usage — account scope, no organization |
| `organizations` | 11 | list, settings, owner, ownership audit, analytics, progress |
| `members` | 7 | list, add, role change, audit, candidate and admin lookup |
| `invitations` + `join-codes` + `organization-invitations` | 6 | the two ways into a workspace |
| `questions` | 2 | create, approve |
| `ai` | 1 | question drafts |
| `assessments` | 4 | create, publish, versions, assignments |
| `assignments` | 5 | mine, detail, start attempt, my attempts, reminders |
| `attempts` | 10 | submit, result, release, review, grade, grade audit, telemetry, tutor, certificate |
| `academy` | 9 | courses, batches, learners, batch assessments, progress, reconcile, feedback |
| `certificates` + `certificate-design` | 5 | mine, display, revoke, audit, design |
| `messaging` | 3 | conversations, messages, partners |
| `talent` | 11 | campaigns, invitations, email invitations, candidates, review, integrity |
| `billing` | 6 | entitlements, options, orders, reconciliation |
| `platform` | 16 | health, organizations, users, roles, billing support, support assessment access |
| `demo` | 2 | accounts, login — demo profile only |

The React app must reach all of these unchanged. No endpoint is being redesigned;
if any change turns out to be needed, the contract goes in this file for approval
before it is relied on.

## Security facts the rewrite must not break

1. **The access token lives only in a JavaScript variable.** It is not in
   `localStorage`, not in a cookie. A full page load therefore ends the session.
   This is deliberate, and it is why the current app is a single page with hash
   routing rather than server-rendered pages per screen.
2. **Every workspace call is scoped by `organizationId` in the path.** The
   frontend never sends a tenant id in a header or a body where the path already
   carries it.
3. **Role gating in the UI is a convenience, not the control.** The server
   enforces it; the sidebar only avoids offering a door that is locked.
4. **Published assessment versions are immutable** and attempt deadlines and
   scoring are the server's. No client-side scoring or timing may be introduced.

## Things the current UI does that are easy to lose in a rewrite

Recorded because each one is a decision someone made, not an accident:

- The pre-attempt notice states that the timer is server-controlled and that an
  attempt in progress is resumed rather than restarted.
- An ungraded attempt shows "Awaiting grading", never a score or a verdict.
- Integrity signals are labelled as evidence for human review, not proof of
  cheating.
- Staff rows name a person *and* keep their id, because the forms act on the id.
- `#/…` routes survive tab switching, so a screen can be linked to.
- There is a skip link, every control has an accessible name, and the focus ring
  is visible on all of them.
- Light and dark both pass WCAG AA; colours come from tokens in `theme.css`, and
  `app.css` declares no colours of its own.
