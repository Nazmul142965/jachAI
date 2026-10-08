# `delivery/academy-talent-ui` — merge review

Asked for: which of the 12 commits are still needed, which are already in the
current code, and which would conflict. Nothing was merged.

Compared `main` (`66ef519`) against `delivery/academy-talent-ui` (`b1d9b78`).

## Short answer

**Do not merge the branch. Eleven of its twelve commits are already in `main`,
applied under different hashes.** Only the progress document is missing, and that
is a copy decision, not a merge.

Evidence: `git diff main delivery/academy-talent-ui --stat` lists **no code file
from this branch's work** — not `AttemptService.java`, `ReviewAttemptView.java`,
`FeedbackView.java`, `ReviewAuditView.java`, `DemoFixtureService.java`,
`SecurityConfig.java`, `app.js`, `app.css`, `index.html` or
`JachAiApplicationTests.java`. Those files are byte-identical in both. Everything
the diff does list is `main` moving ahead of this branch, not the reverse.

Verified per commit by checking for each change's signature in `main`'s tree
rather than trusting matching commit titles.

## Commit by commit

| My commit | Already in `main` as | Verified by | Still needed? |
| --- | --- | --- | --- |
| `0ca5c6e` Name the participant on staff grading screens | `b8dfef9` | `participantDisplayName` in `ReviewAttemptView`, `displayNames` in `AttemptService` | **No** |
| `9dbb6f2` Name the reviewer on audit trails | `6bf80c9` | `actorDisplayName` in `ReviewAuditView` | **No** |
| `063e37e` Name the instructor on published feedback | `12df7c1` | `authorDisplayName` in `FeedbackView` | **No** |
| `eb50929` Certificate list + 320px scroll | `30c65bc` | `certificates/mine?page=0` in `app.js`, `minmax(0, 1fr)` in `app.css` | **No** |
| `1eae84e` Repair the application context test | `1d36c35` | `CertificateDesignService` mocked in `JachAiApplicationTests` | **No** |
| `ee7fcc2` Ungraded attempt is not a failure | `4d8905c` | `not graded yet` in `app.js` | **No** |
| `8cd09c2` Favicon and password-form username | `8ea7a38` | `favicon` in `SecurityConfig`, `autocomplete="username"` in `index.html` | **No** |
| `47caf37` Seed an Academy cohort | `bdb0c5e` | `seedAcademyCohort` in `DemoFixtureService` | **No** |
| `c61854b` Record what the live demo does | — | `CLAUDE_DELIVERY.md` absent from `main` | **Document only** |
| `5ebcdfe` Correct the Journey 2 claim | — | same file | **Document only** |
| `88e4a25` Measured results, journeys 5 and 6 | — | same file | **Document only** |
| `b1d9b78` Manual grading path and what is outstanding | — | same file | **Document only** |

## Conflicts

**None to resolve, because there is nothing left to apply.** Merging the branch
anyway would not conflict either — the code is identical, so git would simply
bring across `CLAUDE_DELIVERY.md` and nothing else. But it would also drag the
branch's merge history into `main` for no gain.

If you want the document, the clean way is to copy that one file rather than merge:

```bash
git show delivery/academy-talent-ui:CLAUDE_DELIVERY.md > CLAUDE_DELIVERY.md
```

273 lines, no code. Worth keeping because it records what was observed live rather
than inferred, including two claims I had to correct, and a reproducible port and
database. Equally, it is a point-in-time record, so retiring it is reasonable too.

## One item that was outstanding and is now closed

The branch left one request open: a rejected request returned
`{"code":"BAD_REQUEST","message":"Invalid request","details":{}}` with no field
named. I wrote the fix but could not apply it — the edit to
`shared/error/GlobalExceptionHandler` was refused as a shared resource.

`main` now has it, as `6fa7efa` "Return safe field details for malformed and
validated requests". Their version is **more conservative than the one I
proposed**: it filters field names through a `safeField()` allowlist and returns a
generic `"Invalid value"` instead of passing the validation message through. That
is the safer call — a validation message can carry more about the constraint than
you want to publish. No further action.

## What this means for the branch

- Keep it as history; nothing in it is lost.
- Do not merge it into `main`.
- Optionally copy `CLAUDE_DELIVERY.md` across.
- The worktree at `E:\Advance Java\jachAI-claude-delivery` is clean and can be
  removed whenever you like, with `git worktree remove`.
