# JachAI frontend — how this is built

Read this before changing anything under `src/main/resources/static` or
`src/main/resources/templates`. It describes the system the existing code
follows so the next change looks like it was written by the same hand.

`ARCHITECTURE.md` is the product specification and wins over this file on
anything about behaviour, permissions or data. This file only covers how the
frontend is put together.

---

## 1. What this frontend is

Plain HTML, CSS and ES modules served from Spring Boot's static resources on
the same origin as the API. There is no npm, no bundler, no build step: the
bytes in `src/main/resources` are the bytes the browser receives.

`ARCHITECTURE.md` §4 specifies this, and two practical things follow from it:

- **A file you edit is live on the next page load.** No watch process.
- **Anything you write must run in a browser as-is.** No JSX, no TypeScript,
  no bare module specifiers, no `node_modules` imports.

If a framework migration is ever decided, the token layer in `theme.css`
ports unchanged — it is plain custom properties. The component layers and
`app.js` would not.

### Why there is no framework

It has been raised and deliberately not done. A migration would need a build
step, a Dockerfile change, and either a second origin (so CORS, cookie and
token handling all change) or a server-side render step the API is specified
not to depend on. It would also discard the browser test harness in §9, which
is currently the only automated check on this UI. None of that is forbidden —
it is just a decision that belongs to whoever owns the deployment, not to a
styling change.

---

## 2. Files and what each one owns

| File | Owns |
| --- | --- |
| `static/theme.css` | **Every token.** Colour, type scale, spacing, radius, elevation, motion, and both themes. Plus the theme control's own styling. |
| `static/theme.js` | The light/dark/system control. Injects itself into `.topbar`. |
| `static/app.css` | Component layer for the authenticated workspace (`index.html`). |
| `static/public.css` | Component layer for the seven public pages. |
| `static/app.js` | The entire workspace: state, routing, API calls, rendering. |
| `templates/index.html` | The workspace shell. Every view's container markup. |
| `templates/*.html` | Public pages. Server-rendered by name only; the controller returns a view name and nothing else. |

**The rule that keeps this coherent: no file except `theme.css` declares a
colour.** Not a hex, not an `rgb()`, not a named colour. If you need a shade
that does not exist, add a token to `theme.css` and use it. `grep -nE
'#[0-9a-fA-F]{3,8}|rgba?\(' src/main/resources/static/app.css` should stay
empty, and the same for `public.css`.

Load order in every page head is `theme.css`, then the component layer. The
inline no-flash script sits between them; see §6.

---

## 3. The design language

Forest green on a faintly green-cast neutral, with lime reserved for
emphasis. Generous radii, tinted shadows (never neutral grey), tight letter
spacing on headings and normal tracking on body text.

### Tokens you will actually use

**Colour — semantic roles, not ramp steps.** Use `var(--surface)`, not
`var(--neutral-0)`. The ramp steps (`--brand-600`, `--neutral-200`, …) are
fixed across both themes; the semantic roles are what the dark theme
reassigns. Reaching past a role into a ramp step is how a component ends up
unreadable in dark mode.

| Role | Use for |
| --- | --- |
| `--canvas` | the page background |
| `--surface` | cards, panels, the top bar |
| `--surface-sunk` | insets inside a surface: tab strips, fieldsets, subpanels |
| `--surface-brand` | a tinted surface indicating selection or brand emphasis |
| `--line` / `--line-strong` | hairlines; `-strong` for hover and dividers that must read |
| `--ink` / `--ink-soft` / `--muted` | primary text, secondary text, supporting text |
| `--brand` / `--brand-hover` / `--brand-ink` | filled brand surfaces, their hover, brand-coloured text |
| `--on-brand` | text on a `--brand` fill. **Never `#fff`** — it is dark in dark mode |
| `--accent` | lime. Sparingly: the brand mark, one badge, a gradient stop |
| `--success-* --warn-* --danger-* --info-*` | status surfaces and their text |

**Spacing** is `--sp-1` (4px) through `--sp-11` (80px) on a 4px base. Use the
scale; do not write `margin: 13px`.

**Type** is `--text-2xs` through `--text-display`. Weights are
`--weight-regular|medium|semibold|bold`. Headings carry
`--tracking-display` or `--tracking-tight`; all-caps labels carry
`--tracking-caps`.

**Radius** is `--r-xs` through `--r-pill`. Cards and panels are `--r-xl`,
list rows and inputs `--r-md`/`--r-sm`, chips `--r-pill`.

**Elevation** is `--e-1` through `--e-4`. A resting card is `--e-2`; hover
lifts to `--e-3`; a modal is `--e-4`. There is no `--e-0` — use no shadow.

**Motion** is `--dur-fast` (120ms) for state the pointer drives and
`--dur-base` (180ms) for everything else, both with `--ease`. Every stylesheet
already has a `prefers-reduced-motion` block; add new transform-based hovers
to it.

### Component vocabulary

Reuse these before inventing a class. They exist in both component layers
where it makes sense.

`.card` `.panel` `.subpanel` — surfaces, in decreasing prominence.
`.button` + `.primary` `.secondary` `.quiet` `.danger-action` `.small`.
`.item` / `.item-main` / `.item-title` / `.item-meta` / `.item-actions` — a
list row. Built by the `item()` helper in `app.js`; do not hand-roll one.
`.item-list` — the grid that holds them.
`.pill` + `.ok` `.warn` `.danger` — a status chip.
`.metric` / `.metric-value` / `.metric-label` — a figure in a `.metrics` grid.
`.empty` — the "nothing here" line inside a list.
`.field-error` — validation text under a field.
`.bar` / `.bar-fill` — a proportion.
`.eyebrow` — the small caps label above a heading.
`.screen-head` / `.section-heading` / `.page-head` — heading rows.

---

## 4. The screen map

What exists, what each screen is for, and the layout each one follows. Build a
new screen by picking the closest pattern in §4.5 rather than starting from a
blank section.

### 4.1 The public surface

Served by `PageController`, which returns a view name and nothing else — there
is no server-side data binding on these pages. Each loads `theme.css` then
`public.css`.

| Page | Path | For | Contains |
| --- | --- | --- | --- |
| Landing | `/` | everyone | Hero, the sign-in / create-account card, a certificate lookup, links into the product pages |
| Academy | `/academy.html` | training institutes | What an Academy cohort run looks like, end to end |
| Talent | `/talent.html` | hiring teams | What a hiring campaign looks like, end to end |
| Plans | `/plans.html` | buyers | FREE and TEAM side by side, a comparison table, FAQ |
| How it works | `/how.html` | everyone | The assessment lifecycle, one engine, two products |
| Certificate | `/certificate.html` | anyone with a code | Public verification. Shows organization, assessment, issue date, validity — and nothing about the holder's identity |
| Verify email | `/verify-email.html` | a new account | Consumes the one-time token, offers a resend |
| Reset password | `/reset-password.html` | a locked-out account | Consumes the one-time token, offers a new request |

**The public page skeleton.** Follow it exactly; the five marketing pages
already do.

```
header.topbar          brand · topnav · (theme control, injected)
main.page              or .page--narrow for a single-column page
  p.eyebrow            small caps, names the section of the product
  h1                   one line, a claim rather than a label
  p.lede               one sentence that earns the claim
  div.cta-row          one primary action, at most two others
  section.section      repeated: .section-head then a grid, list or table
footer.footer          one line of what JachAI is, then a nav
```

### 4.2 The workspace shell

`index.html` is a single page. Everything authenticated lives inside it, and
the access token stays in a JS variable, so there are no separate signed-in
pages to navigate between.

```
header.topbar          brand · note · (theme control) · Sign out
#notice                the status / error banner, role="status"
main.workspace         a two-column grid
  aside.sidebar        organization select · role badge · grouped nav · footnote
  div.content          .page-head (title + subtitle) then exactly one .view
```

Only one `<section class="view">` is visible at a time; `showView` toggles
`.hidden` on all of them and moves focus to the page heading.

**Responsive behaviour**, already in `app.css`:

- Below **900px** the sidebar stops being a column and becomes a horizontal
  bar above the content; the nav scrolls sideways.
- Below **560px** the nav becomes a two-column grid, group headings span both
  columns, list rows stack, and compact buttons grow to a 44px touch target.
- At **320px** nothing may overflow horizontally. There is a check for it.

### 4.3 Navigation, and who sees what

The sidebar is grouped by intent so Academy and Talent read as related but
distinct products. Groups with no visible items are not rendered at all.

| Group | Item | Visible to |
| --- | --- | --- |
| — | Overview | everyone |
| Assessment engine | Members | `ORG_ADMIN` |
| | Question bank, Assessments | `ORG_ADMIN` `INSTRUCTOR` `RECRUITER` |
| Academy *(Academy orgs only)* | Courses & batches / My batches | `ORG_ADMIN` `INSTRUCTOR` `LEARNER` |
| | Batch workspace | `ORG_ADMIN` `INSTRUCTOR` |
| | Messages | `INSTRUCTOR` `LEARNER` |
| | Certificates | `LEARNER` |
| Talent *(Talent orgs only)* | Hiring campaigns, Campaign workspace | `ORG_ADMIN` `INSTRUCTOR` `RECRUITER` |
| My work | My assessments, Assessment detail | `LEARNER` `CANDIDATE` |
| Administration | Platform | verified platform admins |

The learner-facing label changes with the role: staff see **Courses &
batches**, a learner sees **My batches**. Same view, different entry point.

Hiding an item is a courtesy. The server re-checks every request, and nothing
in the UI may suggest that the menu is the permission.

### 4.4 The views

Thirteen, in nav order. "Sections" are the `h2` blocks inside the view.

| View | For | Sections |
| --- | --- | --- |
| `overview` | everyone | Three `.feature` cards (Build with care / Trust the result / Share achievement), a role-aware quick-action row, workspace plan, result-release default, sandbox billing |
| `members` | `ORG_ADMIN` | Transfer ownership, add a registered member, invite by email, the member list, ownership history |
| `questions` | staff | The bank, create a question, AI-assisted draft |
| `assessments` | staff | The list, create, assign a published one, pending result review, submitted answers, review outcomes, instructor feedback |
| `academy` | Academy roles | Courses, batches, enroll a learner, batch assessments, my batches |
| `academy-batch` | Academy staff | One batch on one screen: course → batch selector in the head, its assessments, per-learner progress, cohort analytics, reconcile |
| `messages` | instructor, learner | Conversation list beside one open thread |
| `campaigns` | Talent staff | The list, create, invite a registered candidate, invite by email, email invitations, candidate comparison |
| `talent-campaign` | Talent staff | One campaign: frozen version, candidate comparison, integrity summary, raw event log, review decision, review history |
| `attempts` | learner, candidate | My assignments, with the window state and whether Start is open |
| `assessment-detail` | learner, candidate | One assignment: window, attempts allowed, attempt history, current attempt, released result with topic bars, feedback and AI tutor |
| `certificates` | learner | My certificates, each with its public verification code |
| `platform` | platform admin | Operations counts, storage estimate, organizations, billing support, manual TEAM plan, quota reconciliation, administrators |

### 4.5 The four layout patterns

Nearly every view is one of these. Reuse them.

**A. List screen** — `questions`, `members`, `campaigns`, `attempts`.

```
.screen-head        h2 + one line of what this list is, actions on the right
.filter-row         optional, one control
#…-list.item-list   rows via item(), filled by loadList()
#…-pagination       page controls
.panel              the create / invite form, below the list it adds to
```

The form sits **below** the list it feeds, not above it. The list is what the
person came for.

**B. Workspace screen** — `academy-batch`, `talent-campaign`.

One record, every related thing about it on one screen, no tabs.

```
.screen-head        the record's name in the head, plus a back link
.form-row           the selector that chooses the record
.metrics            the figures that summarise it
.subpanel           repeated: one concern each, h4 label, then a list
```

Reached from a row in its list screen ("Open workspace"), or from a metric
card. Never by typing a URL — the id comes from `state`.

**C. Detail screen** — `assessment-detail`.

One record a learner owns, read-mostly, in the order they care about:
what it is → when it opens → what they have done → the result.

**D. Operations screen** — `platform`.

`.metrics` of counts first, then panels that each name one deliberate action.
Every action states what it does and what it cannot do before it runs.

### 4.6 Not built yet

Four features whose API exists with no UI. §10 lists the endpoints.

| Feature | Belongs in | Shape |
| --- | --- | --- |
| Assessment version history | `assessments`, under the list | Pattern A. Rows of version, question count, published date. Opening a published assessment's history shows what each version froze |
| Batch analytics and progress | `academy-batch` | Pattern B. A `.metrics` row of cohort figures, then per-learner progress rows with `.bar` |
| Tenant quota reconciliation | a billing panel in `overview` | Pattern D. Request, then show the audit. Must not claim a repair the server did not confirm |
| Order detail | `overview` billing, from an order row | Pattern C in a modal or subpanel |

---

## 5. `app.js` — how the workspace works

One module, no router library. Roughly:

```
state            one plain object; every screen reads and writes it
views[]          the list of view ids
showView(key)    toggles #<key>-view, updates nav, title and focus, then loads
```

Each view is a `<section id="<key>-view" class="view">` already present in
`index.html`. `showView` hides all of them and shows one. Nothing is created
from a template string at the top level; containers are markup, contents are
built in JS.

### The helpers to build with

| Helper | Does |
| --- | --- |
| `$(sel)` | `querySelector` |
| `node(tag, text, className)` | an element. **Sets `textContent`, never `innerHTML`.** |
| `button(text, action, className)` | a button already wrapped in `run()` |
| `item(title, meta, actions[])` | a `.item` row |
| `setList(sel, rows, emptyMessage)` | fills a list, or shows the empty line |
| `beginList(sel)` | marks the list `aria-busy` and shows "Loading…" |
| `loadList(sel, load, retryMessage)` | the three states at once: loading, loaded, failed-with-retry |
| `request(path, opts)` | the API call: auth header, refresh-on-401, error shaping |
| `ownOrg(path)` | prefixes the active organization: `/organizations/{id}` + path |
| `run(action)` | runs an async action and shows a notice if it throws |
| `fieldErrors(form, error)` | marks `aria-invalid` and writes `.field-error` from the server's `details` |
| `confirmDialog(message)` | the modal confirm; returns a promise of a boolean |
| `notice(message, isError)` | the top banner |

**Use `loadList` for anything fetched.** It is the reason lists have honest
loading, empty and failure states instead of staying silently blank. A list
that fetches without it is a bug.

**Never build markup with `innerHTML` from server data.** `node()` and
`textContent` exist so tenant content cannot inject markup. The only
`innerHTML` in the codebase is `theme.js` writing its own fixed SVG icons.

### Adding a screen

1. Add the id to `views[]` and to the `names` map in `showView`.
2. Add `<section id="<id>-view" class="view hidden">` to `index.html`.
3. Add a nav button with `data-view="<id>"` under the right `.nav-heading`.
4. Give `showView` a branch that loads it, reading the selected record id
   from `state` rather than from the URL.
5. Keep the list screen that leads to it as the entry point; a detail screen
   is opened from a row, not reached by typing.
6. If it needs a new fixture shape, extend `src/test/frontend/fixtures.js`.

### Guard rails that are not negotiable

- **The access token lives in a JS variable, never in `localStorage` or a
  cookie.** `localStorage` is used for exactly one thing: the theme choice.
- **One-time tokens are stripped from history** with `history.replaceState`
  before the API call. See `verify-email.html`.
- **The UI only helps navigate.** Hiding a button is a courtesy, not a
  permission check — the server re-checks every request, and the frontend
  must never imply otherwise.
- **Never claim an outcome the server did not confirm.** "Queued", not
  "sent". "Estimate", not "quota". If a call cannot confirm delivery, the
  copy says so.

---

## 6. Theming

`theme.css` defines the light palette on `:root`, then the dark palette
twice: once under `prefers-color-scheme: dark` for a visitor who has made no
choice, and once under `[data-theme="dark"]` for one who has. The two blocks
are identical and must stay in step; CSS cannot share a declaration block
across a media-query boundary, which is why they are duplicated rather than
factored.

`theme.js` sets `data-theme` on `<html>` and stores the choice. "System"
means no attribute.

Every page head carries a short inline script that applies a stored choice
before the stylesheet paints. **Keep it inline and keep it before the
component stylesheet** — moved into a file, or deferred, it flashes the wrong
theme on load. Every `localStorage` access in it and in `theme.js` is inside
a `try`, because a private window throws.

When adding a surface, check it in both themes before committing. The common
mistake is `color: #fff` on a brand fill; the fix is `var(--on-brand)`.

---

## 7. Accessibility, as actually practised here

These are checked by the harness in §9 and should not regress.

- One focus style, defined once per stylesheet, on every interactive element.
- Compact buttons reach **44px** at phone widths. There is a check for it.
- **No horizontal overflow at 320px.** There is a check for it. Long tenant
  strings carry `overflow-wrap: anywhere`; grid children carry `min-width: 0`.
- State is never carried by colour alone. A warning says it is a warning in
  words; the colour repeats that. See the `.status` tone classes on the auth
  pages.
- Lists are `aria-busy` while loading. Notices are `role="status"` with
  `aria-live="polite"`.
- Changing view moves focus to the page heading.
- Nav buttons carry `aria-current`; toggles carry `aria-pressed`.
- Every icon-only control has an `aria-label`; decorative SVG is
  `aria-hidden="true" focusable="false"`.

---

## 8. Writing copy

The product's promise is a trustworthy assessment lifecycle, and the copy
carries most of that weight.

- Say what the server did, not what you hope it did.
- Name the thing a person recognises — a person's name, not an id.
- A destructive confirm states what changes and what it cannot do.
- Never imply a payment that did not happen. The manual TEAM grant panel says
  "manual grant — not a payment" for this reason; keep that wording.
- Failure copy names the failure and offers the next action. "Could not load"
  with a Retry, not a blank panel.

---

## 9. Verifying a change

```bash
node src/test/frontend/run-journeys.js
```

79 checks across 6 journeys, in a real browser engine, against fixtures whose
field names come from the real Java records. It serves the actual templates
and static files — no Spring, no Mongo. **Run it before every commit.**

A green run means the UI renders, guards and recovers against the documented
contract. It is **not** live verification; no database, mail, AI or payment
provider is involved, and saying otherwise in a commit message or a progress
doc is how this project previously lost track of what was real.

For a syntax-only check: `node --check src/main/resources/static/app.js`.

To look at pages by hand, serve the resources directory:

```bash
node -e "require('./src/test/frontend/static-server.js').startServer(require('path').resolve('src/main/resources'),4173).then(s=>console.log(s.url))"
```

Only `index.html` is covered by the harness. The seven public pages are
checked by hand — in both themes, at 320px, 375px and desktop.

---

## 10. API coverage

The frontend calls ~93 of the 101 real API endpoints. Regenerate this by
listing `@*Mapping` annotations across `src/main/java/**/*Controller.java`
and matching them against path literals in `app.js`, remembering that
`ownOrg()` prefixes `/organizations/{id}` so a literal is a **suffix** of the
real path.

Deliberately not called from the workspace:

- `PageController` — those are the templates themselves.
- `SslCommerzCallback`, `BkashCallback` — server-to-server. Access comes from
  a server-verified transaction, never a browser redirect.
- The four `AuthController` reset/verify endpoints — called by
  `reset-password.html` and `verify-email.html`, not `app.js`.

Known uncovered, with no UI yet:

| Endpoint | Would belong in |
| --- | --- |
| `GET`/`POST` `…/assessments/{id}/versions`, `PUT` `…/assessments/{id}/draft` | the Assessments view |
| `GET` `…/batches/{b}/assessments/{a}/analytics` and `/progress` | the Batch workspace |
| `GET`/`POST` `…/billing/entitlements/usage-reconciliation` | an org-admin billing panel |
| `GET` `…/billing/orders/{id}` | order detail from the billing list |

---

## 11. Things not to do

- Do not add a colour outside `theme.css`.
- Do not add a build step, a framework or a CDN script without that being the
  decision being made, separately and explicitly.
- Do not put anything except the theme choice in `localStorage`.
- Do not use `innerHTML` with server data.
- Do not describe a fixture run as live verification.
- Do not mark work complete in a progress document before it is committed.
  That has already happened once here and cost a session's trust.
