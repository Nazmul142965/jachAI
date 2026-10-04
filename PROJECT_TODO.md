# JachAI — current delivery checklist

Updated: 2026-10-04. `ARCHITECTURE.md` §14 defines acceptance; this file tracks what still needs proof or implementation. A checked item means only the stated scope passed, not that the whole product is production-ready.

## Working foundation

- [x] Modular Spring Boot API, MongoDB models, tenant roles, JWT auth, assessment lifecycle, Academy and Talent workflows, billing/AI adapters, and deployment recipe exist in `main`.
- [x] Academy and Talent core journeys passed isolated local HTTP/MongoDB smoke runs; see `HANDOFF.md`. This is not browser, Atlas, SMTP, or provider verification.
- [x] Six-character registration/reset validation and local email-verification flow passed focused tests and a real local HTTP run (`61bde89`). Local verification-link opener committed (`a7aac3a`).
- [x] Ownership audit recovery passed local MongoDB integration (`b4c5971`). AI quota Redis keys now hide tenant/user IDs; unit tests passed (`0e85bd6`).
- [x] The committed `frontend/ui-refresh` branch was merged into `main` (`5a7fc5f`). Public theme assets were fixed and checked through a focused Spring test and local HTTP responses (`2b27096`). The UI still needs live-browser assessment and design iteration.
- [x] A concurrent owner-transfer test passed against disposable local MongoDB: one winner, one rejected transfer, one durable audit event. This is not an Atlas concurrency result.
- [x] Outbox concurrent claim, expired-lease fencing, and durable retry/backoff passed two tests against disposable local MongoDB. This does not prove SMTP delivery or exactly-once email delivery.
- [x] Academy batch-analytics aggregation passed a disposable local-Mongo test covering latest submitted attempt, pending grade, zero score, empty cohort, and tenant/batch isolation. Atlas performance remains unverified.

## Next implementation and integration

- [ ] Continue the separate frontend redesign worktree and merge only reviewed, committed slices into `main`. The first UI-refresh branch was already integrated; do not redo its three API panels or overwrite the frontend agent's worktree.
- [ ] Complete real-browser Academy learner/instructor, Talent recruiter/candidate, billing, and platform-admin walkthroughs against a current local API. Fixture journeys alone are not live API proof.
- [ ] Decide and implement pending-account retention. User requested automatic removal after a few unverified days; the exact duration and whether the rule applies to existing accounts require explicit approval before enabling deletion.
- [ ] Verify remaining MongoDB-sensitive workflows on a dedicated `jachai_it_*` database: membership concurrency, telemetry TTL, billing reconciliation, and other audit-recovery paths. Local owner concurrency, outbox lease/retry, and batch analytics are closed; Atlas behavior remains separate.

## External-service gates

- [ ] Run live Redis checks for rate limits, token revocation, published-version cache, and atomic AI quotas. No local Redis listener/binary is available; the installed WSL distribution currently fails to start.
- [ ] Configure a private SMTP test account and verify registration/reset/invitation delivery and outbox retry. Do not put credentials in the repository.
- [ ] Verify Gemini question drafting, Groq tutoring, and optional Ollama locally with real provider responses; retain human approval and deterministic official scoring.
- [ ] Verify Google OAuth with a real web client ID and exact allowed origin.
- [ ] Verify direct bKash and SSLCOMMERZ **sandbox-only** checkouts, lost-callback recovery, independent server-side validation, and billing notices. No live-money endpoints or credentials.
- [ ] Build and run the Docker image, check production liveness/readiness with MongoDB and Redis, and rehearse deployment/backup/recovery. Docker is not yet available in this environment.

## Release gate

- [ ] Reconcile the current frontend branch, run the appropriate full Java/frontend suites once on the integrated revision, complete security/cross-tenant review, and record the exact live evidence in `HANDOFF.md`. Do not infer production readiness from startup or fixture tests.
