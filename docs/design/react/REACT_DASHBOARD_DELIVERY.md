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

---

# Phase 2 — research, design directions, recommendation

**Visible prototype:** `docs/prototypes/directions.html`. Open it directly in a
browser, or serve the folder (`python -m http.server 8103`) and open
`http://127.0.0.1:8103/directions.html`. It contains the three directions and
three screens — personal dashboard, organizer dashboard, question screen — each at
desktop 1280 and mobile 375, drawn with the product's own `theme.css` tokens.

## How to read the research, and its limits

Accessed **8 October 2026**. These are **secondary sources** — comparison write-ups,
pattern articles and product documentation — not live walkthroughs: I did not hold
accounts in these products and did not sign in to any of them. So the table below
separates what a source states from what I concluded.

- **Reported** = stated by the cited source.
- **Inferred** = my reading of what it implies for JachAI. Mine to defend, not the
  source's.

No layout, wording, trademark or visual identity is copied from any of them. What
is taken is structural reasoning, which is not ownable and is the point of looking.

| # | Product / source | Category | Reported | Inferred for JachAI |
| --- | --- | --- | --- | --- |
| 1 | [SaaS workspace switcher patterns](https://www.saasui.design/blog/saas-workspace-organization-switcher-ux-patterns) (cites Linear, Notion, Slack, Vercel, Figma, Loom) | Multi-tenant SaaS | The switcher "decides which reality every other screen renders". Place it top-left in sidebar or chrome with name plus a distinct avatar; list each workspace with the viewer's role on the row; keep the personal account menu a **separate** home (bottom-left or top-right); persist last-used workspace; never show a stale page while the header claims another tenant | This is JachAI's defining structure, not a nicety. One account is a learner in one org and owner of another, and practice sits outside every org. Switcher top-left, account bottom-left, role on every row |
| 2 | [HackerRank](https://www.g2.com/compare/hackerrank-developer-skills-platform-vs-testgorilla) | B2B assessment | Auto-graded coding challenges run against real test cases; take-homes and live interviews in one product; favoured by larger enterprises | Auto-scoring must stay visibly server-side. JachAI's equivalent is deterministic server scoring plus manual grading for written answers |
| 3 | [Codility](https://resources.trueability.com/comparisons/technical-assessment-platform-comparison-hackerrank-codility-testgorilla-coderpad-abilityscreen) | B2B assessment | Differentiates on **methodology and compliance** rather than question volume | JachAI's real asset is the same: immutable published versions, server-held deadlines, audit trails. The UI should show this, not hide it |
| 4 | [TestGorilla](https://www.getapp.com/hr-employee-management-software/a/hackerrank/compare/testgorilla/) | B2B assessment | 300+ validated tests; anti-cheat via webcam snapshots, full-screen enforcement, question randomisation; popular with small businesses | JachAI's integrity signals are deliberately weaker and honestly labelled. Keep that framing — evidence for review, never an accusation |
| 5 | [CoderPad / AbilityScreen](https://resources.trueability.com/comparisons/technical-assessment-platform-comparison-hackerrank-codility-testgorilla-coderpad-abilityscreen) | B2B assessment | CoderPad leads live collaborative interviews; AbilityScreen differentiates on real-environment task execution | Out of scope. JachAI is asynchronous; do not build toward live sessions |
| 6 | [Canvas LMS](https://wooclap.com/en/blog/canvas-vs-google-classroom/) | Education admin | SpeedGrader exists so an instructor can grade a roster quickly and give feedback; strong course analytics | The strongest argument for grading **focus mode**: graders work a queue, and per-item chrome is friction. JachAI already has the queue |
| 7 | [Google Classroom](https://www.getapp.com.au/compare/91646/131851/canvas-lms/vs/google-classroom) | Education admin | Valued for fast class setup and intuitive daily workflow; simplicity over feature depth | Cohort setup must be a short path. Our own demo proved the opposite risk: a workspace with no course offers nothing to do |
| 8 | [Duolingo streak research](https://blog.duolingo.com/how-duolingo-streak-builds-habit) and [Khan Academy progress](https://support.khanacademy.org/hc/articles/360054115071) | B2C practice | Daily targets, streaks, progress meters, celebratory feedback; a 7-day streak measurably lifts retention | **Take the structure, refuse the confetti.** A daily target and visible quota map exactly onto the AI practice quota. Badges and celebration would undercut a product whose other half issues certificates an employer is meant to trust |

### The one finding that decides the architecture

Source 1 states the switcher decides which reality every screen renders, and that
switching tenant and managing yourself must be different levers. JachAI has a
third scope the article does not cover: **personal practice, which belongs to no
workspace at all**. So the shell needs three visible scopes, not two, and the
current sidebar's "My account" / workspace split is already most of the answer.
That is why the recommendation below evolves it rather than replacing it.

## The three directions

| | A — Two scopes, one shell | B — Task-first focus mode | C — Product switcher console |
| --- | --- | --- | --- |
| Shape | Persistent sidebar; switcher pinned top, account pinned bottom | No persistent nav; home is a ranked queue; work fills the screen | Academy / Talent / Practice as top-level products, each with its own nav |
| Strength | Conventional, nothing to learn; both scopes always visible | Answers "what now" first; ideal while attempting or grading | Reads as a large platform; room for future products |
| Weakness | Chrome is a distraction and an accidental exit during an attempt | 15 screens with no visible map; discovery collapses | **Wrong boundary** — scope is the workspace, not the product |
| Verdict | **Shell** | **Two screens** | Rejected |

C is rejected on a specific ground, not taste: a person who is a learner in one
organization and an owner of another would switch product *and* workspace to get
anywhere. It optimises for a catalogue of products when the user's real question is
"which workspace am I in".

## Recommendation

**Direction A as the shell, Direction B for exactly two screens: taking an attempt,
and grading one.**

A everywhere people navigate, because the account/workspace/practice split is the
product's defining structure and must stay visible. B where people *work*, because
during a timed, server-scored attempt the sidebar is a distraction and an
accidental exit, and because a grader working a queue (source 6) wants the next
item, not the navigation.

This is one system with two modes, not two designs: same tokens, same type scale,
same components. Focus mode removes chrome; it does not introduce a second visual
language.

## Information architecture

Three scopes, always distinguishable:

```
ACCOUNT            WORKSPACE  (switcher decides which)      FOCUS
  My dashboard       Overview                                 Attempt
  Practice           Assessment Studio   (staff)              Grading
  Profile            Academy / Talent    (by org type)
  Your workspaces    Members, Billing    (owner)
                     My Assessments      (participant)
```

### First login, role by role

| Role | Lands on | First thing it must say |
| --- | --- | --- |
| Learner | My dashboard | The assignment waiting, with its window and attempts left; practice quota |
| Candidate | My dashboard | The invited assessment; nothing about courses or cohorts |
| Instructor | Overview | The grading queue if non-empty, otherwise the next step that fills it |
| Owner (Academy) | Overview | Cohort state and plan usage; a named action when the workspace is empty |
| Recruiter | Overview | Candidates awaiting review; comparison one click away |
| New user, no workspace | My dashboard | Practice works immediately; joining by code or invite offered. **Never an empty organization selector** |

A learner is never asked to create an organization. Owner-only controls are hidden,
not shown disabled.

### Responsive behaviour

Sidebar at >=1100px; below that it collapses to a bottom tab bar of five items with
the switcher staying in the top bar. Focus mode is single-column on mobile with the
question map as a horizontal strip. Target 320px as the floor, as the current UI
already does.

### Tokens

Reuse `theme.css` unchanged — the palette, type scale and both themes already pass
WCAG AA on every leaf text node (measured, not assumed). React consumes the same CSS
custom properties. This rewrite changes structure, not colour.

## What each prototype fixes

| Screen | Problem today | What changes |
| --- | --- | --- |
| Personal dashboard | Role, quota and next action are present but compete with everything else | One recommended next action leads; quota is a meter, not a sentence; practice and workspace work are visibly separate |
| Organizer dashboard | Counts and queues read as a list of numbers | Each count is a queue that opens the screen owning it; ungraded attempts show "Awaiting grading" and never a verdict |
| Question screen | Full app chrome during a timed attempt | Focus mode: question map always visible, autosave state explicit, server-ownership of the clock stated on the screen |
| Generation | Progress is unclear and failures imply quota was spent | Explicit progress, and quota charged only on a produced question — stated in the UI |
| Empty states | An empty list is a dead end | Each empty state explains the thing and offers the action that creates it |
| Errors | "Invalid request" with no field | Field-level, from the server's message, naming the field — never echoing the request |

---

# Phase 3 — the decision this needs from you

**Nothing has been migrated.** No production route changed, no old file removed,
no React dependency added. This branch currently contains two things: the
inventory above, and one static prototype document. That is deliberate — the rest
waits on your decision.

## The honest comparison: React, or improve what exists

The question is not "is React better". It is whether the problems in the current
UI are caused by the absence of a framework. Most of them are not.

| Problem in the current UI | Needs React? | Why |
| --- | --- | --- |
| Role, quota and next action compete for attention on the home screen | **No** | This is layout and copy. Fixable in `app.css` and the render functions |
| Ungraded attempts showed a verdict | **No** | One null guard. Already fixed this way on the other branch |
| Empty states are dead ends | **No** | Content, not architecture |
| Sideways scroll at 320px | **No** | One CSS line |
| Field-level validation errors | **No** | Server-side change, independent of the frontend |
| Generation progress is unclear | **Partly** | Doable today, but this is genuinely fiddly state: pending, streaming, produced, failed-without-charge |
| `app.js` is 4787 lines in one file with no modules | **Yes, really** | There is no component boundary, so every screen shares one scope. Changing one screen risks another |
| No types across 116 API paths | **Yes, really** | Every field name is a string literal. The `/certificates` vs `/certificates/mine` bug was exactly this: a typo'd path that silently 404'd and was swallowed |
| No way to test a screen in isolation | **Yes, really** | There is currently no frontend test at all. Not one |
| Re-render is manual DOM mutation | **Yes** | Each list rebuilds by hand; stale-render bugs are a standing risk, and the code carries explicit load-token guards to work around it |

**What this means.** Roughly two thirds of the visible problems can be fixed in the
current stack in a fraction of the time. The real case for React is not appearance
— it is that the current frontend has no module boundaries, no types and no tests,
across a surface of 116 endpoints and 5 roles. That is a maintainability argument,
and it is a legitimate one, but it should be made honestly rather than dressed up
as a design problem.

### Option 1 — Improve the current UI, no framework

- **Effort:** roughly 2–4 days for the visible problems above.
- **Regression risk:** **low.** Changes are local, and the app has no build step,
  so what you read is what runs.
- **You get:** most of the visual and usability gain in the prototypes.
- **You do not get:** types, tests, or module boundaries. `app.js` keeps growing.

### Option 2 — Full React + TypeScript migration

- **Effort:** realistically **3–5 weeks** for genuine parity, not a weekend. The
  surface is 15 screens, 22 views, 116 endpoints, 5 roles plus a platform-admin
  flag, with pagination, audit trails, and a token that lives only in memory.
- **Regression risk:** **high, and concentrated in specific places:**
  1. **The in-memory token.** A React app with client-side routing must preserve
     "a full page load ends the session". Get the refresh flow wrong and either
     sessions break or the token leaks into storage, which is a security
     regression, not a UI one.
  2. **Role gating.** 5 roles × 15 screens. Every missed gate shows someone a door
     that 403s — or worse, looks like it worked.
  3. **Pagination and audit screens.** Easy to rebuild approximately and get
     subtly wrong.
  4. **Server-authoritative behaviour.** Timers, scoring, release state. A
     client-side optimisation here is a correctness bug.
- **You get:** typed API clients, testable components, and a frontend that can be
  changed one screen at a time.
- **Build:** Vite + TypeScript, output compiled into the Spring package so it stays
  same-origin and needs no Node server in production.

### Option 3 — The one I would actually suggest

**Do Option 1 now, and let it decide Option 2.**

Fix the layout, empty states, errors and the 320px issues in the current stack this
week — low risk, and the prototypes are mostly achievable there. Then migrate to
React **one screen at a time behind the existing hash router**, starting with
Practice, because it is the most self-contained (account scope, 5 endpoints, no
role gating, no tenant). If that slice is genuinely better to work on, continue.
If it is not, you have lost days rather than weeks, and the improved UI is already
shipped.

This also avoids the thing the brief rightly warns about: two competing dashboards
in production. One screen at a time inside one router is never two dashboards.

## How existing functionality is preserved, in any option

- The REST API is not touched. All 116 paths stay as they are. If a change ever
  looks necessary, the contract comes to you here first — none is proposed.
- Public, verification, reset and certificate pages stay Thymeleaf and are out of
  scope entirely.
- Security behaviour is carried over verbatim: token in memory only, every
  workspace call scoped by `organizationId` in the path, server-side authorization
  unchanged, UI gating treated as convenience and never as the control.
- A parity checklist, screen by screen, would be written and signed off **before**
  any old file is retired. Nothing is deleted on my judgement.

## What I recommend you look at, and what I need from you

Open `docs/prototypes/directions.html` and look at four things:

1. The **workspace switcher** top-left and the account menu bottom-left — that
   split is the one structural proposal here.
2. The **organizer dashboard**: whether counts-as-queues reads better than the
   current list of numbers.
3. The **practice screen**: generation progress, all questions readable at once,
   and the "nothing was charged" failure state.
4. The **attempt screen**: whether removing the sidebar during a timed attempt
   feels right to you.

Then tell me which of the three options you want. I have not started any of them.

## Status

| Item | State |
| --- | --- |
| Inventory of current UI | Done, committed |
| Research, 8 sources | Done, committed |
| Three directions + recommendation | Done, committed |
| Prototypes: personal, organizer, practice, attempt — desktop + mobile | Done, committed |
| React/no-React comparison, effort, risk | Done, this section |
| Any migration work | **Not started, awaiting your decision** |
| Production dashboard | **Untouched** |
| Old UI files | **Untouched** |
| `main` and the backend agent's files | **Untouched** |

---

# Phase 4 — Option 3 in progress

You approved Option 3: improve the current UI first, then one limited React pilot
on Practice, and decide the rest afterwards. Nothing beyond that has been started.

## 4a. Current UI, improved in place

| Commit | What it fixes | Verified |
| --- | --- | --- |
| `567fe24` | Eight empty lists now name the control that fills them | Ownership history and release queue read as written, live |
| `5e7afed` | A phone showed navigation instead of content | Sidebar 699px → 212px, first card 830px → 390px on a 375×780 phone |
| `5fc96ef` | Explanatory text ran to 858px, past 110 characters | Capped at 78ch |

Two things I did **not** change, having checked them:

- **Error states.** `loadList` already appends the server's own message and a
  Retry button, so the short prefix is a prefix, not the whole story. My first
  pass misread 57 of 60 messages as dead ends; most are terminal and correct. An
  audit log with no entries needs no call to action.
- **The remaining 52 empty messages.** Rewriting them all would be churn.

## 4b. React Practice pilot

**Off unless the URL carries `?pilot=practice`.** With the flag absent the bundle
is never fetched — verified by watching `fetch` through a full sign-in and visit
to Practice. No second dashboard, no second route, no second navigation.

### Why it mounts inside the page

The access token is held in a variable in `app.js` and nowhere else. A React app
served at its own URL could not see it, and copying it into storage to work
around that would be a security regression, not a migration. **The host hands the
pilot a `request` function and never the token.**

### Parity against the old Practice screen

| # | Behaviour | Pilot | Evidence |
| --- | --- | --- | --- |
| 1 | Three loads tolerate partial failure | Yes | `Promise.allSettled`, each slice carries its own error |
| 2 | Question list, searchable | Yes | 5 starter questions listed and filterable |
| 3 | Selection survives reload, else first | Yes | effect keyed on the loaded list |
| 4 | Generate takes topic and difficulty | Yes | plus the optional `brief` the API accepts |
| 5 | Generation progress is explicit | Yes | spinner plus "usually 5–10 seconds", `role="status"` |
| 6 | Quota line, both allowances | Yes | `0/20 … (20 left) · 2/20 … (18 left) today` |
| 7 | Generate disabled at 0 remaining | Yes | plus a line saying answering still works |
| 8 | Submit disabled at 0 remaining or no question | Yes | |
| 9 | Answer returns verdict and explanation | Yes | "Not correct", correct option named, explanation shown |
| 10 | History with correct / review and a time | Yes | updates after answering |
| 11 | Reload | Yes | |
| 12 | Usage failure pauses both actions | Yes | and says so |

**Beyond parity:** the quota is shown as two meters as well as a sentence; the
question list marks what you have already answered and how it went; a failed
generation re-reads the allowance from the server rather than asserting nothing
was charged.

### Verified live on 8102

- Five questions load; answering returns verdict, correct option and explanation.
- The allowance moves **server-side** to `2/20 answers checked (18 left)`.
- History and the question list both update.
- Switching to the old screen afterwards shows the same `2/20` — same account,
  same API, same server state.
- Security after a full session in the pilot: nothing matching a token in
  `localStorage`, `sessionStorage` or cookies, and no token-named global.
- `npx tsc -b --noEmit` clean; `node --check app.js` clean.

### Two mistakes worth recording

1. **I wrote the API types from assumption, not from the controller.** The
   difficulty scale is `BEGINNER|INTERMEDIATE|ADVANCED`, not `EASY|MEDIUM|HARD`;
   generating takes no question type; a practice question is always single
   choice; and answering carries `requestId`, an idempotency key. The server
   answered `Request validation failed` twice before I read the Java. This is
   precisely the bug class typing exists to prevent, and inventing the types
   defeated it. They are now transcribed, with the three traps named in `api.ts`.
2. **Vite's library mode does not substitute `process.env.NODE_ENV`,** so the
   first build shipped React's development build: 1085 kB against 378 kB, 86 kB
   gzipped.

Also worth knowing for anyone testing: **Spring copies static resources at
startup**, so a rebuilt bundle is not served until the app restarts.

### Build

```
npm --prefix frontend install
npm --prefix frontend run build      # → src/main/resources/static/pilot/practice.js
```

`node_modules`, the bundle and the TypeScript build cache are gitignored. Nothing
needs a Node server at runtime: the output is one static file served same-origin
by Spring.

### One change outside the frontend

`shared/config/SecurityConfig` permits `/pilot/practice.js`. It is a static asset
exactly like `/app.js` beside it — fetched by a dynamic import that carries no
`Authorization` header, holding no secret, with the data still behind the
authenticated API. Flagged here because that package is not mine by default.

## What I have not done, and am waiting on

- No other screen is in React. No production route changed.
- The old Practice screen is untouched and is still what everyone sees.
- Nothing is merged into `main`.

**The decision I need: continue screen by screen, stop here, or adjust the
approach first.**
