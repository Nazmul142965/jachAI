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
- At the baseline, Maven reported 22 passing tests. Later verified milestones: 30 after password reset/JWT, 34 after Phase 3 result release, 42 after Academy certificates, 46 after frontend/attempt resume, 58 after Talent campaigns, 67 after access-token revocation/security-version tests, 74 after Academy feedback, 84 after Academy course/batch/enrollment, 92 after batch assessment distribution/progress, 101 after Academy direct messaging, 108 after Talent integrity telemetry, 116 after Talent email invitations, 126 after encrypted email outbox, 132 after request rate limits, and 141 after plan entitlement gates. This does not verify live MongoDB, Redis, SMTP, Gemini, Groq, Ollama, or a browser walkthrough.
- Maven on this Windows/JDK 21 environment sometimes emits `Fatal Error: Cannot close compiler resources` without Java diagnostics; a rerun has succeeded. Treat a failed run as unverified until a later successful full test run, not as proof of success.
- Local test profile excludes MongoDB and uses mocks in the application smoke test. A live infrastructure integration test is still needed.

## Work in progress right now

Committed slices: Phase 2 identity/security `17c49c8`; Phase 3 result-release `f6e6431`; Academy certificates `0abdf4f`; attempt history `b929115`; template/dashboard foundation `841d6b4`; Talent campaigns/candidate directory `fe76cb0`; access-token revocation/security version `226d57d`; Academy feedback `cf7e741`; Academy catalog/enrollment `5c40383`; batch assessment distribution/progress `06570a0`; Academy direct messaging `f32e1cf`; Talent integrity telemetry `7bf0041`; Talent email invitations `16d5a24`; encrypted email outbox `e84e733`; request rate limits `0f7d43f`. Current work adds FREE/TEAM subscription documents, usage reservations, server-side plan gates, and overview UI. Verify Git status and whether this slice has since been committed. Live MongoDB/Redis/email delivery and complete phase gates remain unverified. There is no `MONGODB_URI` in this shell, no local MongoDB listener, and no Docker; the user configured the Atlas URI only in IntelliJ.

## Remaining major work

Use `ARCHITECTURE.md` section 14 as the authoritative phase gates. Key gaps include live verification of token revocation, message, telemetry, invitation, email-job TTL, rate-limit counters, and entitlement reservations against MongoDB/Redis; organization-level result settings and richer review UI; Academy aggregate cohort analytics, certificate emails and audit; quota backfill/reconciliation and Stripe sandbox; frontend completeness and browser walkthrough; deployment; and live integration/tenant-isolation tests. General assignment still requires an existing active member; Talent email invites can onboard an external candidate. AI routing exists for Gemini drafts, Groq tutor, and Ollama local, but only mock use-case tests have run; no real provider request has been verified. Do not call the product complete because the unit suite passes.

## Next-agent prompt

> Continue implementing JachAI in `E:\Advance Java\jachAI` to completion against `ARCHITECTURE.md`. First read `HANDOFF.md`, `ARCHITECTURE.md`, `README.md`, any `AGENTS.md`, `git status`, and current source/tests; treat the worktree as authoritative and preserve user edits. Preserve HTML under `templates`, CSS/JS under `static`, and REST API boundaries. Review the plan entitlement slice and its tests, then implement Stripe sandbox webhooks/checkout or quota reconciliation. Add tests for business rules and cross-tenant security at every slice. Keep MongoDB as source of truth, Redis for bounded cache/quotas/revocation, and AI outside official scoring. Implement both Academy and Talent plus a responsive REST-consuming frontend and SaaS operations, then run appropriate unit/integration/runtime checks. AI routing code covers Gemini drafts, Groq tutor, and Ollama local, but no live calls have been verified; do not claim AI complete. Do not use or expose real credentials; the user will add keys near the end. Commit locally only when a phase's acceptance gates are genuinely met, with a phase-specific message; never label the baseline or partial work as a completed phase. Update this handoff file as evidence changes, and report precise remaining gaps instead of claiming completion prematurely.
