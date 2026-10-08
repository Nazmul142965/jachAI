# JachAI backend

This is the current backend entry point. [ARCHITECTURE.md](ARCHITECTURE.md) defines product and security invariants; [SYSTEM.md](SYSTEM.md) covers runtime configuration and release gates. Historical implementation reports, test counts, and branch handoffs are preserved in [docs/archive/completed/](docs/archive/completed/README.md), not treated as current instructions.

## Modules and boundaries

The Java 21 Spring Boot modular monolith keeps authentication, organizations, Academy assessments, Talent campaigns, personal practice/AI, billing, mail, support, and platform operations in their own packages under `src/main/java/com/jachai`. Services enforce authorization and tenant isolation; the browser's selected role, timer, answer key, and payment status are never authoritative.

MongoDB is the durable store for accounts, assessments, attempts, orders, audit events, and email jobs. Redis supplies production rate limits, quota reservations, revocation acceleration, and published-version caching. Provider integrations remain optional until configured, but enabled payment entitlements require independent server-side sandbox validation and idempotent application.

## Demo and real accounts

The six one-click demo identities work on the configured database in the `demo`, `local`, or `prod` profile. On a public host, `DEMO_PUBLIC_ACCESS=true` and a valid HTTPS `APP_PUBLIC_BASE_URL` are required. `DEMO_SHARED_DATABASE` is retained only as a legacy configuration name and is not an access gate. Demo IDs are reserved and must remain barred from password/reset, profile/avatar changes, and Google linking; real accounts retain those features. Demo workspaces are shared, not private faculty accounts.

## Verification and changes

- Run `./mvnw test` with Java 21 for the integrated revision. Frontend checks are documented in [FRONTEND.md](FRONTEND.md).
- Mongo integration tests are opt-in with a dedicated `MONGODB_TEST_URI` pointing to a `jachai_it_*` database. Never run destructive tests against customer, demo, or production data. The historical test procedure and results remain in [MONGODB_INTEGRATION_TESTS.md](docs/archive/completed/MONGODB_INTEGRATION_TESTS.md).
- A passing unit suite does not prove SMTP delivery, OAuth, AI provider calls, payment callbacks, or hosted MongoDB/Redis readiness. The latest release evidence is archived at [RELEASE_CHECK_2026-10-09.md](docs/archive/completed/RELEASE_CHECK_2026-10-09.md); repeat its gates on the actual deployed revision.
- Preserve concurrent worktrees and user-owned untracked files. Review a branch's current diff before cherry-picking; old handoff branches are not automatically newer than `main`.

## Where to read deeper

Backend implementation history and prior open questions: [BACKEND_REMAINING.md](docs/archive/completed/BACKEND_REMAINING.md). Practice quota and replay decisions: [AI_PRACTICE_DELIVERY.md](docs/archive/completed/AI_PRACTICE_DELIVERY.md). The operational audit-repair procedure remains live at [docs/runbook-audit-recovery.md](docs/runbook-audit-recovery.md). These records preserve evidence; [ARCHITECTURE.md](ARCHITECTURE.md), current code, and [SYSTEM.md](SYSTEM.md) decide present behavior.
