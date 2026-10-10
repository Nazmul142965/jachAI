# JachAI interface standard

How a screen in this product is written and laid out, so the next one needs no
argument. It records decisions that are already applied, not wishes. Where a rule
has a way to check it, the check is named.

The audience is a student on a phone as often as an organizer at a desk, and many
of them read Bangla more easily than English. That sets most of what follows.

## Where the rules come from

Established guidance, applied to this product rather than reinvented:

- Nielsen Norman Group, [ten usability heuristics](https://www.nngroup.com/articles/ten-usability-heuristics/) —
  especially *match between the system and the real world* (our words), *recognition
  rather than recall* (labels over icons), *visibility of system status* (loading,
  saved, sent) and *help users recover* (every error names the next step).
- W3C [WCAG 2.2](https://www.w3.org/TR/WCAG22/), level AA — contrast, focus, target
  size. Contrast on both themes was measured earlier and is unchanged here.
- GOV.UK [content design](https://www.gov.uk/guidance/content-design) — plain words,
  one idea per sentence, buttons that say what they do.
- The category's own products (see `docs/design/react/REACT_DASHBOARD_DELIVERY.md`,
  eight sources) for the structure: a switcher that decides which organization every
  screen belongs to, and a personal area that belongs to none.

## The rules

**1. A page answers three questions, in this order.** Where am I, what should I do
now, what else is here. The page head carries the first (organization or "Personal",
then the title, then one line of purpose). Home then leads with exactly one primary
action. Everything else is below it, smaller.
*Check:* Home has one `.home-next` card and it is the first thing under the greeting.

**2. Use the words people already use.**

| We say | We no longer say | Why |
| --- | --- | --- |
| Home | My dashboard | "Dashboard" is a manager's word |
| Test | Assessment | A learner sits a test |
| Organization | Workspace | "Workspace" names nothing a student has |
| Create tests | Assessment Studio | A verb, and what it does |
| Courses | Academy Workspace | |
| Campaigns | Talent Campaigns | |
| Admin | Administration | |
| Owner | Owner / Org Admin | A permission, not a name |

Buttons are verbs ("Open test", "Send"). A sentence has one idea. Internal nouns
(*participant access*, *frozen published version*, *server-enforced*) do not appear
in copy a learner reads. Organizer screens still say "assessment" in places; moving
them is the next pass, and the glossary above is the rule for it.

**3. Look like the thing it is.** Messages is a chat: conversations on the left, a
thread on the right, my messages on the right in the brand colour, theirs on the left,
the time inside each bubble, a day divider. Enter sends, Shift+Enter breaks a line.
The box you type in is on screen without scrolling on a 768px laptop. On a phone the
list and the thread stack, and choosing someone brings the thread into view.
*Check:* `composerBottom <= innerHeight` at 1366×768.

**4. Every state says what to do next.** Loading, empty, error. A notice lasts 3.5s
(6s for an error) and a click dismisses it, so one action's banner is never still up
during the next. An empty list names the control that fills it.

**5. The phone is the floor.** 375px, with 320px not overflowing. The menu starts
closed so the page opens on its content; the toggle opens it as a two-column grid and
choosing a screen closes it. Tap targets are 44px.
*Check:* the mobile journeys (`menu starts closed`, `no horizontal overflow`).

**6. One colour means one thing.** Aqua is the brand and the chrome, green means
verified or passed, coral asks for attention, red failed. A shortcut card is not a
result, so it is not green. Chat uses the brand colour, not the accent.

**7. Reloading, Back, or another page never signs you out.** Every product people
already trust works this way. The access token still lives only in memory; what
survives is the ability to ask for a new one, held in an `HttpOnly` cookie
(`jachai_rt`) that script cannot read. On load, if this browser had a session, the page
asks the server for a fresh token and puts the person back on the screen the address
names. Signing out clears it, and clears the hint, so a reload after signing out does
not sign anyone back in.
Why it is safe, and why each piece must stay:

| Piece | Reason |
| --- | --- |
| `HttpOnly` | A script that runs on the page cannot copy the credential away |
| `SameSite=Strict`, `Path=/api/v1/auth` | Never sent with a request another site causes, nor to other API calls |
| `Secure` follows `APP_PUBLIC_BASE_URL` | TLS ends at the host's proxy and forwarded headers are deliberately not trusted, so the request cannot say whether it was https |
| No `Max-Age` | Gone when the browser closes. Students use shared lab machines |
| `X-JachAI-Session` header required to use the cookie | Only this site's own script can add it; a second, independent check |
| Refresh token is single-use | A stolen, already-used token is dead. Another tab's rotation wins over a stale in-memory token |
| A `localStorage` hint gates the restore | Without it every visitor's page load would ask the server, and the server counts per address; behind one proxy that is one shared bucket |

*Check:* `SessionCookieTests` (16), and in a browser: sign in, reload, still signed in;
open `/reset-password.html`, click the logo, still signed in; sign out, reload, signed out.

**8. One menu, the same on every page, with icons that carry no meaning alone.**
Academy, Talent, Pricing, How it works, How it's built, Sign in. Line icons (SVG masks
in `theme.css`), never emoji: emoji differ on every device, cannot follow the theme, and
are read aloud as their cartoon names. An icon is `aria-hidden`; the label is the meaning.
The same family is used in the sidebar. Six links wrap onto two rows on a phone rather
than push the page sideways.

## Language: English and Bangla

`static/i18n.js`. The switch is in the top bar, works signed out, and is remembered
in `localStorage` (a convenience; never the access token).

- **The English text is the key.** `t('My tests')`, or `data-t` on an element whose
  text is the key. A string with no Bangla entry shows its English, so a screen is
  never blank, only mixed.
- **No concatenation.** Write `t('Revisit {topic}', { topic })`, not
  `'Revisit ' + topic`; word order differs. Pass counts as numbers: in Bangla they
  become Bengali digits (`১৫ মিনিট`). Use two keys for one and many.
- **Dates and times** take `getLocale()`.
- **Type.** Noto Sans Bengali, line-height 1.7, and `letter-spacing: normal` — the
  negative tracking used on Latin headings pulls Bengali conjuncts together.
- A label that wraps an input needs `<label><span data-t>…</span><input>`, because
  `data-t` replaces the element's text and would otherwise remove the input.
- A string set by script after load must go through `t()` where it is set, and the
  screen must repaint on `jachai:language` (see `relabelShell`). A reload is not an
  option: the access token lives only in memory, so a reload signs the person out.

**How it works now.** The page is translated by what it *says*: a text node (or a
`placeholder`, `aria-label` or `title`) whose whole text is a key in `static/i18n.js` is
shown in Bangla and put back on switching to English. A new screen is therefore covered
the moment its English is in the dictionary; nothing has to be tagged. A `MutationObserver`
translates text the screens add later. Sentences built from parts ("33% used · Across this
workspace") are translated piece by piece: split at " · ", then each piece by exact match
or by a pattern in `PATTERNS`. A piece nothing recognises is left alone, so a name, an
email or an id inside a sentence is never altered. Dates follow the language everywhere.
`t()` is still the right call for text the code itself composes with numbers.

**To add a screen or a string:** write the English, add it to the dictionary, switch the
language and look. Strings that carry a number or a name go in `PATTERNS` with `bd()` for
Bengali digits. Do not translate what a person typed or what the data holds: course
names, emails, question text. They stay as written.

**Coverage.** Every screen for every role is translated, checked by walking each
sidebar item as all seven demo roles in Bangla and listing any English left over. What
remains is data (names, question text) and a few server diagnostics in Billing.
Server error messages that arrive as text stay English until the API returns codes.

The AI Tutor answers in Bangla when the interface is Bangla (`language` on the request),
unless the person writes in another language.

The switch shows both options with the chosen one filled, so nobody has to guess what a
single button will do.

**Type scale.** Do not raise it app-wide to fix one screen: it makes everything look
zoomed, and on a display at 125% scaling more so. It was raised one notch once and
reverted (base 15px).

## Not React

Decided with the owner: the current interface is improved in place. The problems were
wording, structure and information density, none of which a framework fixes. The
Practice pilot stays behind `?pilot=practice`; its parity record is
`docs/practice-parity.md`. Revisit if a screen needs state the page genuinely cannot
hold, not for look.

## How to check a change

```bash
node src/test/frontend/run-journeys.js        # 154 checks, seconds, mocked API
```

and, with the app running, click through every sidebar item as each demo role and
confirm no console error, no red notice, no horizontal overflow. That loop found the
phone menu override that had returned from `main`.
