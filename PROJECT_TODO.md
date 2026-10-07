# JachAI — current delivery checklist

Updated: 2026-10-07. `ARCHITECTURE.md` §14 defines acceptance; this file tracks what still needs proof or implementation. A checked item means only the stated scope passed, not that the whole product is production-ready.

## Working foundation

- [x] Modular Spring Boot API, MongoDB models, tenant roles, JWT auth, assessment lifecycle, Academy and Talent workflows, billing/AI adapters, and deployment recipe exist in `main`.
- [x] Academy and Talent core journeys passed isolated local HTTP/MongoDB smoke runs; see `HANDOFF.md`. This is not browser, Atlas, SMTP, or provider verification.
- [x] Six-character registration/reset validation and local email-verification flow passed focused tests and a real local HTTP run (`61bde89`). Local verification-link opener committed (`a7aac3a`).
- [x] Ownership audit recovery passed local MongoDB integration (`b4c5971`). AI quota Redis keys now hide tenant/user IDs; unit tests passed (`0e85bd6`).
- [x] The committed `frontend/ui-refresh` branch was merged into `main` (`5a7fc5f`). Public theme assets were fixed and checked through a focused Spring test and local HTTP responses (`2b27096`). The UI still needs live-browser assessment and design iteration.
- [x] A concurrent owner-transfer test passed against disposable local MongoDB: one winner, one rejected transfer, one durable audit event. This is not an Atlas concurrency result.
- [x] Outbox concurrent claim, pre-send stale-lease guard, expired-lease fencing, and durable retry/backoff passed focused unit/local-Mongo tests. This does not prove SMTP delivery or exactly-once email delivery.
- [x] Academy batch-analytics aggregation passed a disposable local-Mongo test covering latest submitted attempt, pending grade, zero score, empty cohort, and tenant/batch isolation. Atlas performance remains unverified.
- [x] Membership role changes now repair a pending prior audit before replacing its recovery marker; focused unit and local-Mongo tests passed. Atlas concurrency remains open.
- [x] An opt-in `local,smtp` transport and isolated `jachai_smtp_*` launcher path allow real inbox testing without production mode; profile-selection and fail-closed checks passed. No live SMTP send has been performed.
- [x] The frontend agent's committed command-centre Overview and Assessment Studio tabs were merged into `main` (`1689b96`). The merged fixture browser suite passed 121/121 checks. The updated loopback demo on port 8095 served its assets, owner login and both organization lists worked, and the Overview's pending-review, draft-assessment and usage-reconciliation APIs returned successfully for Academy and Talent. This is not a signed-in live-browser walkthrough.
- [x] The committed Academy cohort, Talent comparison, and released-result UI slices were merged through `df1cee3`. Port 8095 was restarted from `main` and served the new result markup and CSS (HTTP 200, health `UP`). In isolated `jachai_demo_public_20261004`, the learner completed the Academy batch assignment through real answer-save and submit APIs. Owner analytics now reports one active, assigned, submitted, and graded learner; learner result GET reports a released 33% result with three topics. This is live local API evidence, not a signed-in browser walkthrough.
- [x] An opt-in real Chrome check now signs in against the populated loopback Spring/Mongo demo without API fixtures. Learner result and Academy cohort rendered in light and dark modes with no JavaScript exceptions; four screenshots are under ignored `target/frontend-live-evidence/`. This is scoped live-browser evidence, not a full-role product acceptance.
- [x] Claude's committed header/brand navigation fix was merged into `main` (`be579c4`); clicking the signed-in brand now stays in the same document. Academy staff roster and progress responses now include learner display names (`9e8869c`), and the UI renders them. The restarted port-8095 demo passed a real-browser brand, learner-result, cohort, and display-name check; this does not cover every role or every outbound link.
- [x] Talent recruiter comparison and candidate assignment now have scoped real-browser proof against the loopback Spring/Mongo demo. The comparison API includes the candidate's display name (`a439409`), and the UI shows it instead of a UUID. Light/dark recruiter screenshots and a candidate screenshot are under ignored `target/frontend-live-evidence/`. Review submission, billing, and platform-admin actions remain separate gates.
- [x] The Talent owner's read-only Billing view passed a real local-browser check (`a297da0`): FREE plan limits, quota-reconciliation report, order history, sandbox-only/no-real-money disclosure, and checkout button state. No checkout or quota repair was invoked; provider callbacks and platform-admin billing actions remain unverified.
- [x] A guarded opt-in local-browser Talent review saved a HOLD decision for the disposable demo candidate and rendered the resulting decision and immutable review history. The same journey showed three raw integrity events with the client-reported/not-proof-of-misconduct caveat. This does not prove other decisions, concurrent reviewers, or Atlas behavior.
- [x] The development and production profiles now both use a private Atlas URI with database names outside that URI: `jachai_dev` and `jachai_prod`. The local launcher no longer starts or forces a local MongoDB server. The earlier local-Mongo startup (`3929bd4`) tested the wrong architecture and is superseded; it does not prove Atlas authentication. The user's Atlas `bad auth` failure still needs a valid, rotated database-user password before live verification.

- [x] Operator audit recovery for membership and ownership is implemented and merged (`f859392`). A platform admin gets a bounded tenant-scoped dry run that writes nothing, and an insert-only repair that is idempotent, re-reads each record before writing, and refuses records whose markers are incomplete. The membership action derivation is now defined once and shared with `MembershipService`, so the operator tool cannot write an event the owning service would not. 31 focused plus 5 local-Mongo tests passed, and the full 547-test suite passed on a dedicated `jachai_it_` database. A runbook is at `docs/runbook-audit-recovery.md`. Transitions whose markers were overwritten by a later change remain unrecoverable by design, and no endpoint has been exercised over HTTP.

## Current coordination (2026-10-07)

- [x] Claude's later staff-name, certificate-list, mobile-overflow, and application-context fixes were reviewed and cherry-picked into `main` as `b8dfef9` through `1d36c35`. His branch is still separate; its retrospective delivery notes and unfinished browser journeys are not a completed release gate. Do not merge the older branch wholesale over newer auth/billing changes.
- [x] Seven-day unverified-account retention is implemented in `UnverifiedAccountCleanup` and the verification/login paths (`92ccdb0`); the earlier pending-retention checkbox below is superseded. Live Atlas cleanup timing and race behaviour remain unverified.
- [x] Google ID-token audience/authorized-party validation was tightened (`c9536cf`); 12 focused auth tests passed. A real Google sign-in remains unverified. The local app was not running when checked on 2026-10-07, so the newly entered `GOOGLE_CLIENT_ID` could not yet be confirmed at runtime.
- [x] Separate agent assignments and owner-only account/setup checklist are committed (`d39c962`). Agent 01's Claude branch has partial delivery; agent 02's identity scope is the current main-writer scope. Agent 03 AI/practice, 04 billing/platform, 05 runtime, and 06 final integration have not been assigned or declared complete merely because prompt files exist.

## Next implementation and integration

- [ ] Continue the separate frontend redesign worktree and merge only reviewed, committed slices into `main`. Do not redo the existing versions, order-detail, or usage-reconciliation UI panels, and do not overwrite the frontend agent's worktree. The Academy learner-name gap is closed in the local demo.
- [ ] Complete real-browser Academy instructor workflows beyond the cohort, broader Talent review decisions and integrity behavior beyond one demo HOLD submission, payment/order-detail interactions beyond the read-only Billing view, and platform-admin walkthroughs against a current local API. Learner released result, Academy cohort, recruiter comparison, candidate assignment, one guarded Talent review, and read-only Billing have scoped live-browser proof; fixture journeys alone are not live API proof.
- [ ] Verify the implemented seven-day pending-account cleanup against a disposable Atlas test database, including verification/cleanup concurrency and records with memberships. Do not delete real accounts as a test.
- [ ] Verify remaining MongoDB-sensitive workflows on a dedicated `jachai_it_*` database: membership concurrency, telemetry TTL, and billing reconciliation. Membership and ownership audit recovery is now closed (`f859392`). Local owner concurrency, membership prior-audit recovery, outbox lease/retry, and batch analytics are closed; Atlas behavior remains separate.

## External-service gates

- [ ] Run live Redis checks for rate limits, token revocation, published-version cache, and atomic AI quotas. No local Redis listener/binary is available; the installed WSL distribution currently fails to start.
- [ ] Configure a private SMTP test account and verify registration/reset/invitation delivery and outbox retry. Do not put credentials in the repository.
- [ ] Verify Gemini question drafting, Groq tutoring, and optional Ollama locally with real provider responses; retain human approval and deterministic official scoring.
- [ ] Verify Google OAuth with a real web client ID and exact allowed origin.
- [ ] Verify direct bKash and SSLCOMMERZ **sandbox-only** checkouts, lost-callback recovery, independent server-side validation, and billing notices. No live-money endpoints or credentials.
- [ ] Build and run the Docker image, check production liveness/readiness with MongoDB and Redis, and rehearse deployment/backup/recovery. Docker is not yet available in this environment.

## Release gate

- [ ] Reconcile the current frontend branch, run the appropriate full Java/frontend suites once on the integrated revision, complete security/cross-tenant review, and record the exact live evidence in `HANDOFF.md`. Do not infer production readiness from startup or fixture tests.
