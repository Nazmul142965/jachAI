# Handoff — React UI worktree

Written so this can be picked up by someone else without re-deriving anything.
**Read this before `REACT_DASHBOARD_DELIVERY.md`**, which is the long record.

## Where you are

- Worktree: `E:\Advance Java\jachAI-react-ui`
- Branch: `frontend/react-dashboard`, cut from `main` at `66ef519`
- **Never touch** `E:\Advance Java\jachAI` (main folder). It is on the
  `support-center` branch with another agent's uncommitted files.
- Nothing is merged into `main`. Do not merge.

## Run it

```powershell
# Mongo, if not already listening on 27017
$mongod = 'C:\Users\nazmul\Documents\Codex\local-tools\mongodb\server\mongodb-win32-x86_64-windows-8.0.29\bin\mongod.exe'
Start-Process $mongod -ArgumentList '--dbpath','C:\Users\nazmul\AppData\Local\Temp\claude-mongo-data','--bind_ip','127.0.0.1','--port','27017' -WindowStyle Hidden

# App. Port and database are explicit on purpose: 8093/8095 are not this run.
Set-Location 'E:\Advance Java\jachAI-react-ui'
$env:MONGODB_URI = 'mongodb://127.0.0.1:27017'
$env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks\ms-21.0.12.1'
# Google client id, copied from the 8080 run's own public config endpoint.
# Never print it, never put it in a file, never commit it.
$env:GOOGLE_CLIENT_ID = (Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/auth/google/config').clientId
.\scripts\start-local.ps1 -Demo -Port 8102 -DatabaseName jachai_demo_react
```

- App: `http://127.0.0.1:8102/`
- React Practice pilot: `http://127.0.0.1:8102/?pilot=practice` → sign in with the
  **Learner** demo button → Practice.

## Three traps that cost real time

1. **Spring copies static resources at startup.** Editing `app.js`, `app.css`,
   `index.html`, or rebuilding the pilot bundle, does nothing until you restart
   the app. A stale bundle once sent an old request body and looked like an API bug.
2. **The display is at 125% OS scaling** (`devicePixelRatio` 1.25). The browser
   pane's CSS viewport maxes out near 1536px, so a true 1920×1080 CSS viewport is
   not reachable here. Do not label a 1536-wide screenshot as 1920.
3. **Do not write API types from assumption.** Read the controller. See below.

## Build the pilot

```bash
npm --prefix frontend install
npm --prefix frontend run typecheck
npm --prefix frontend run build    # → src/main/resources/static/pilot/practice.js
```

`node_modules`, the bundle and `*.tsbuildinfo` are gitignored. Nothing needs a
Node server at runtime; the output is one static file Spring serves same-origin.

## Done, committed

| Commit | What |
| --- | --- |
| `831dd1d` | Inventory: 5 roles, 15 screens, 22 views, 116 API paths |
| `756971a` | Research (8 sources), 3 design directions, recommendation, prototypes at `docs/prototypes/directions.html` |
| `cbe2eef` | Merge review of `delivery/academy-talent-ui` — **do not merge it**, 11 of 12 commits are already in `main` |
| `567fe24` | Eight empty lists name the control that fills them |
| `5e7afed` | Mobile nav: a phone showed navigation instead of content (sidebar 699px → 212px) |
| `5fc96ef` | Prose line length capped at 78ch |
| `351be46` | React Practice pilot, off unless `?pilot=practice` |
| `9fefaa4` | Stop tracking the TypeScript build cache |
| `867774e` | Parity record for the pilot |

## In flight, NOT yet committed

A visual-quality pass answering five specific complaints about the Practice
screen at 8102, plus "fix all pages" and "fix the login page".

Changed so far, all verified in the browser except where noted:

| Change | File | State |
| --- | --- | --- |
| Type scale raised one notch (base 15px → 16px) | `theme.css` | Verified, app-wide |
| `.content` centred (`margin-inline: auto`) — a 1920 screen had ~300px dead space on the right | `app.css` | Applied, **not yet verified at a wide viewport** (see trap 2) |
| Login columns align to the top (`align-items: start`) — a tall sign-in card used to float the hero down and overflow top and bottom | `app.css` | Verified at 1536 |
| Pilot layout rewritten: work left, context right; answer card moved from 1332px to 391px; page 2217px → 1577px | `app.css`, `Practice.tsx` | Verified at 1366 |
| Duplicate "Practice" heading removed (the pilot no longer renders its own `<h1>`) | `Practice.tsx` | Verified |
| Page scope follows the screen: eyebrow "Your account" vs "JachAI workspace", role banner hidden on account screens, no org name in the subtitle | `index.html`, `app.js` | Verified both ways |
| **Remount bug**: leaving Practice and returning left it blank, because `replaceChildren()` emptied a container React still held a root against | `app.js`, `mount.tsx` | Fixed and rebuilt, **needs re-verification in the browser** |
| Removed a duplicated CSS region (lines 518–658) created by an earlier marker-based edit that used markers in the wrong order | `app.css` | Verified — `.practice-prompt` now resolves to 22px, not 18px |

### The immediate next step

1. Restart 8102 (trap 1).
2. Open `?pilot=practice`, sign in as Learner, go to Practice, **navigate to
   Overview and back to Practice** — it must still render. That is the remount
   fix and it is the one thing not yet re-verified.
3. Answer a question end to end: verdict appears, the allowance increments
   server-side, history and the question list update.
4. Screenshot at 1366×768 and 375. Then commit.

## Google client ID — done, with one caveat

`/api/v1/auth/google/config` on 8102 was `{"clientId":""}` because the ID comes
from the `GOOGLE_CLIENT_ID` environment variable and that run did not have it.
Reading it from the 8080 run's own public config endpoint and passing it in the
start command fixes it: verified non-empty, 72 characters, ending
`.apps.googleusercontent.com`.

### CONFIRMED BLOCKER: 8102 is not an authorised origin

This is no longer a caveat. The browser console on 8102 reports, from Google:

```
[GSI_LOGGER]: The given origin is not allowed for the given client ID.
403 from https://accounts.google.com/...
```

So the configuration half is fixed - `/api/v1/auth/google/config` returns the ID,
and the button renders - but **Google sign-in cannot work on 8102 until the owner
adds the origin.** It is not a code problem and nothing in this repository can fix
it.

**What the owner must do**, in the Google Cloud console for the same OAuth 2.0
Web client already used by the 8080 run:

> APIs & Services → Credentials → that OAuth client → **Authorised JavaScript
> origins** → add `http://127.0.0.1:8102` → Save. Changes can take a few minutes.

Add `http://localhost:8102` as well if that host is ever used, since Google
treats it as a different origin.

**Do not claim Google sign-in works.** Email sign-in and the demo role buttons are
unaffected and work normally.

## Rules carried from the owner

- Do not start migrating any other screen. The pilot is Practice only, and the
  owner decides screen by screen.
- Do not create a second dashboard, route or navigation in production.
- Do not remove the old Practice screen.
- Preserve: the existing API, the quota behaviour, the answer flow, and the
  in-memory token. The pilot must never receive the token — it is handed a
  `request` function.
- Do not touch the main folder or the backend agent's files.

## API contract, transcribed (do not guess these again)

From `PracticeController`, `PracticeService`, `PersonalPracticeGenerationService`:

- `GET /practice/questions` → `QuestionView[] { id, topic, prompt, options[] }`.
  No `type`, no `difficulty`. A practice question is **always single choice**.
- `GET /practice/usage` → `{ utcDay, used, limit, remaining, generated,
  generationLimit, generationRemaining }`
- `GET /practice/answers/mine?page=0` → `HistoryPage { content[], number,
  totalPages, totalElements }`
- `POST /practice/questions/generate` → `{ topic, difficulty, brief? }` where
  difficulty is **`BEGINNER | INTERMEDIATE | ADVANCED`**, not EASY/MEDIUM/HARD
- `POST /practice/answers` → `{ requestId (UUID), questionId, optionId }`.
  `requestId` is an **idempotency key**: a fresh one per submission so a retry is
  not charged twice.

## One change outside the frontend

`shared/config/SecurityConfig` permits `/pilot/practice.js`. It is a static asset
exactly like `/app.js`: fetched by a dynamic import that carries no
`Authorization` header, holding no secret, with the data still behind the
authenticated API. That package is not this branch's by default, so flag it when
integrating — and note the other agent has uncommitted changes to the same file.
