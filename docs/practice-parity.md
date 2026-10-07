# Practice screen — parity checklist

Digest of the existing implementation, taken from `static/app.js` and
`templates/index.html` so neither has to be re-read. Line numbers are from
`app.js` at commit `5fc96ef`.

## API (5 paths, account scope — no organization in any of them)

| Call | Used for |
| --- | --- |
| `GET /practice/questions` | the question bank for this account |
| `GET /practice/usage` | today's two quotas |
| `GET /practice/answers/mine?page=0` | answer history |
| `POST /practice/questions/generate` | generate one question |
| `POST /practice/answers` | check an answer |

## How the page loads (`loadPractice`, L4087)

`Promise.allSettled` over questions, usage and history, so **one failing call does
not blank the screen**. Each has its own fallback message. Parity must keep this.

## Behaviour to match

| # | Behaviour | Source |
| --- | --- | --- |
| 1 | Question picker lists `topic — prompt`, keeps the previous selection if it still exists, else selects the first | L4094-4103 |
| 2 | Searchable question list, filtered by topic or prompt text | `#practice-question-filter` |
| 3 | Generate form takes topic, type and difficulty | `#practice-generate-form`, L4195 |
| 4 | Generation status has busy and error states, each with a message *and* a detail line | `setPracticeGenerationState`, L4126 |
| 5 | A "Browse" control appears only while generating | L4133 |
| 6 | Usage reads `created (N left)` and `checked (M left)` for today | `renderPracticeUsage`, L4134 |
| 7 | Generate is disabled at `generationRemaining === 0` | L4109 |
| 8 | Submit is disabled at `remaining === 0` or with no questions | L4110 |
| 9 | Answering posts and shows correct/incorrect with the explanation | L4253-4272 |
| 10 | History shows `Correct` / `Review this` with a relative time | L4118-4120 |
| 11 | Retry reloads everything | `#practice-retry`, L4194 |
| 12 | Usage failure disables both actions and says the allowance could not be checked | L4112-4115 |

## Security facts the pilot must not break

`request()` (L213) is the only thing that touches the token:

- Reads `state.accessToken` from memory and sets `Authorization: Bearer`.
- On 401 it refreshes once via `state.refreshToken` and replays.
- Nothing is written to `localStorage`, `sessionStorage` or a cookie.

**Therefore the pilot is mounted inside the existing page and is handed a
`request` function. It never receives the token itself.** A separate React page
on its own URL could not see the in-memory token, and copying the token anywhere
to work around that would be a security regression, not a migration.
