# JachAI working handoff

Updated: 2026-10-02. This file is a living checkpoint, not a claim that the project is finished. Inspect the worktree and `ARCHITECTURE.md` before relying on any status below.

## Objective and user decisions

- Complete the single Spring Boot application and responsive frontend described in `ARCHITECTURE.md`; no microservices.
- The product has Academy and Talent modules sharing one assessment engine.
- Preserve concurrent user changes. Work autonomously; ask only for genuinely required choices or credentials.
- Use Lombok `@RequiredArgsConstructor` when it removes ordinary dependency constructors, but keep Java records for immutable DTOs/documents and do not add unnecessary constructors.
- Add tests alongside each vertical workflow. Commit locally after a phase's actual acceptance gates pass, with phase-specific changes and a clear message.
- Keep cloud AI on free tiers. Gemini is for question drafting, Groq for tutoring; Ollama is the local option. User will obtain keys near project completion. Never request or paste secrets into chat/code/commits.
- MongoDB Atlas Free has a hard storage limit; keep persistent AI logs/history bounded or absent, use TTL for temporary data, and monitor storage.

## Repository and verification status

- Project: `E:\Advance Java\jachAI`.
- Local Git branch `main`; baseline commit `524db0b` records the state that existed when Git was first initialized. It is **not** a completed-phase commit. Subsequent identity changes are a Phase 2-scoped slice, not a release sign-off. No GitHub remote was configured at this checkpoint.
- At the baseline, Maven reported 22 passing tests. After the password-reset/JWT commit, 30 passed. The latest Phase 3 result-release/topic-breakdown slice passed all 34 tests. This does not verify live MongoDB, Redis, Gemini, or Groq.
- Maven on this Windows/JDK 21 environment sometimes emits `Fatal Error: Cannot close compiler resources` without Java diagnostics; a rerun has succeeded. Treat a failed run as unverified until a later successful full test run, not as proof of success.
- Local test profile excludes MongoDB and uses mocks in the application smoke test. A live infrastructure integration test is still needed.

## Work in progress right now

The Phase 2 identity/security slice is committed as `17c49c8`. The current Phase 3 slice adds version-frozen `IMMEDIATE`/`MANUAL` release policy, staff release route, candidate-safe pending result, topic breakdown, and manual-release tutor gate. All 34 unit/smoke tests passed. Verify Git status to see whether this slice has since been committed. Live MongoDB/email delivery and complete phase gates remain unverified. There is no `MONGODB_URI` in this shell, no local MongoDB listener, and no Docker; the user configured the Atlas URI only in IntelliJ.

## Remaining major work

Use `ARCHITECTURE.md` section 14 as the authoritative phase gates. Key gaps include immediate access-token revocation; organization-level result settings and review UI; Academy courses/batches/feedback/certificates/messaging; Talent campaigns/invitations/comparison/review/shortlisting/integrity telemetry; SaaS entitlements/Stripe/email jobs/rate limits; responsive frontend; deployment; and live integration/tenant-isolation tests. Do not call the product complete because the unit suite passes.

## Next-agent prompt

> Continue implementing JachAI in `E:\Advance Java\jachAI` to completion against `ARCHITECTURE.md`. First read `HANDOFF.md`, `ARCHITECTURE.md`, `README.md`, any `AGENTS.md`, `git status`, and current source/tests; treat the worktree as authoritative and preserve user edits. Review the latest Phase 3 result-release/topic-breakdown slice and its tests, then proceed to the next missing vertical workflow. Add tests for business rules and cross-tenant security at every slice. Keep MongoDB as source of truth, Redis for bounded cache/quotas/revocation, and AI outside official scoring. Implement both Academy and Talent plus a responsive REST-consuming frontend and SaaS operations, then run appropriate unit/integration/runtime checks. Do not use or expose real credentials; the user will add Gemini/Groq keys near the end. Commit locally only when a phase's acceptance gates are genuinely met, with a phase-specific message; never label the baseline or partial work as a completed phase. Update this handoff file as evidence changes, and report precise remaining gaps instead of claiming completion prematurely.
