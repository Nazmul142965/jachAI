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
- At the baseline, Maven reported 22 passing tests. Later verified milestones: 30 after password reset/JWT, 34 after Phase 3 result release, 42 after Academy certificates, 46 after frontend/attempt resume, and 58 after the current Talent campaign/candidate-directory slice. This does not verify live MongoDB, Redis, Gemini, Groq, Ollama, or a browser walkthrough.
- Maven on this Windows/JDK 21 environment sometimes emits `Fatal Error: Cannot close compiler resources` without Java diagnostics; a rerun has succeeded. Treat a failed run as unverified until a later successful full test run, not as proof of success.
- Local test profile excludes MongoDB and uses mocks in the application smoke test. A live infrastructure integration test is still needed.

## Work in progress right now

The Phase 2 identity/security slice is committed as `17c49c8`; the Phase 3 result-release/topic-breakdown slice as `f6e6431`; the Academy certificate slice as `0abdf4f`; attempt history as `b929115`; and the template/dashboard foundation as `841d6b4`. Current work adds Talent hiring campaigns, campaign-bound candidate assignments, latest-attempt comparison, review decisions, a tenant-scoped candidate directory, and a Talent dashboard view. All 58 unit/smoke tests passed. Verify Git status to see whether this slice has since been committed. Live MongoDB/email delivery and complete phase gates remain unverified. There is no `MONGODB_URI` in this shell, no local MongoDB listener, and no Docker; the user configured the Atlas URI only in IntelliJ.

## Remaining major work

Use `ARCHITECTURE.md` section 14 as the authoritative phase gates. Key gaps include immediate access-token revocation; organization-level result settings and richer review UI; Academy courses/batches/feedback/certificate emails and audit/messaging; Talent external email invitations/integrity telemetry; SaaS entitlements/Stripe/email jobs/rate limits; frontend completeness and browser walkthrough; deployment; and live integration/tenant-isolation tests. Assignment currently requires an existing active member. AI routing exists for Gemini drafts, Groq tutor, and Ollama local, but only mock use-case tests have run; no real provider request has been verified. Do not call the product complete because the unit suite passes.

## Next-agent prompt

> Continue implementing JachAI in `E:\Advance Java\jachAI` to completion against `ARCHITECTURE.md`. First read `HANDOFF.md`, `ARCHITECTURE.md`, `README.md`, any `AGENTS.md`, `git status`, and current source/tests; treat the worktree as authoritative and preserve user edits. Preserve HTML under `templates`, CSS/JS under `static`, and REST API boundaries. Review the Talent campaign slice and its tests, then proceed to the next missing vertical workflow. Add tests for business rules and cross-tenant security at every slice. Keep MongoDB as source of truth, Redis for bounded cache/quotas/revocation, and AI outside official scoring. Implement both Academy and Talent plus a responsive REST-consuming frontend and SaaS operations, then run appropriate unit/integration/runtime checks. AI routing code covers Gemini drafts, Groq tutor, and Ollama local, but no live calls have been verified; do not claim AI complete. Do not use or expose real credentials; the user will add keys near the end. Commit locally only when a phase's acceptance gates are genuinely met, with a phase-specific message; never label the baseline or partial work as a completed phase. Update this handoff file as evidence changes, and report precise remaining gaps instead of claiming completion prematurely.
