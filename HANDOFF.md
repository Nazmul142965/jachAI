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
- At the baseline, Maven reported 22 passing tests. After the current password-reset/JWT edits, a full Maven run reported 30 passing tests. This does not verify live MongoDB, Redis, Gemini, or Groq.
- Maven on this Windows/JDK 21 environment sometimes emits `Fatal Error: Cannot close compiler resources` without Java diagnostics; a rerun has succeeded. Treat a failed run as unverified until a later successful full test run, not as proof of success.
- Local test profile excludes MongoDB and uses mocks in the application smoke test. A live infrastructure integration test is still needed.

## Work in progress right now

Identity/tenant phase hardening includes password reset token document/repository, email sender method, auth service/controller routes, token TTL indexes, public reset page, JWT `purpose=access` validation, and related tests. The full unit/smoke suite passed (30 tests), but live MongoDB/email delivery and complete phase gate verification are still pending. There is no `MONGODB_URI` in this shell, no local MongoDB listener, and no Docker; the user configured the Atlas URI only in IntelliJ. Inspect `git status` for any user changes before editing.

## Remaining major work

Use `ARCHITECTURE.md` section 14 as the authoritative phase gates. Key gaps at this checkpoint include immediate access-token revocation; full result-release policy, topic analytics, Academy courses/batches/feedback/certificates/messaging, Talent campaigns/invitations/comparison/review/shortlisting/integrity telemetry, SaaS entitlements/Stripe/email jobs/rate limits, responsive frontend, deployment, and live integration/tenant-isolation tests. Do not call the product complete because the unit suite passes.

## Next-agent prompt

> Continue implementing JachAI in `E:\Advance Java\jachAI` to completion against `ARCHITECTURE.md`. First read `HANDOFF.md`, `ARCHITECTURE.md`, `README.md`, any `AGENTS.md`, `git status`, and current source/tests; treat the worktree as authoritative and preserve user edits. Review the uncommitted password-reset/JWT-purpose work and verify its phase gate, then proceed to the next missing vertical workflow. Add tests for business rules and cross-tenant security at every slice. Keep MongoDB as source of truth, Redis for bounded cache/quotas/revocation, and AI outside official scoring. Implement both Academy and Talent plus a responsive REST-consuming frontend and SaaS operations, then run appropriate unit/integration/runtime checks. Do not use or expose real credentials; the user will add Gemini/Groq keys near the end. Commit locally only when a phase's acceptance gates are genuinely met, with a phase-specific message; never label the baseline or partial work as a completed phase. Update this handoff file as evidence changes, and report precise remaining gaps instead of claiming completion prematurely.
