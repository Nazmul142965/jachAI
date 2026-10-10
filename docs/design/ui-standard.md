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

**Covered:** sign-in and registration, the shell (sidebar, page head, account menu),
Home, My organizations, Messages. **Not yet:** Practice, My tests, Certificates,
Profile, and every organizer screen. Server messages (validation, errors) stay English
until the API returns codes the client can map.

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
