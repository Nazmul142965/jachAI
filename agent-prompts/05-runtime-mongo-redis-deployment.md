# Agent 05 — MongoDB, Redis, deployment and security runtime

Create a new worktree/branch from current `main`. Read `ARCHITECTURE.md` including section 14, `README.md`, `HANDOFF.md`, `DEPLOYMENT.md` and Git status. Own `config`, deployment scripts/Dockerfile, `DEPLOYMENT.md`, and opt-in integration tests under a distinct `src/test/java/com/jachai/release/` package. Do not rewrite business modules or UI. Preserve dirty work; no reset/force.

Build a reproducible release harness: Java 21 compile/package, Docker image if Docker is available, liveness versus readiness, MongoDB/Redis connectivity, indexes/TTL, tenant isolation, CAS/concurrency, outbox leases, rate limits, token revocation, and forwarded-IP/proxy trust. Integration tests must refuse any Mongo URI whose database name does not begin `jachai_it_`; use disposable data only, no broad deletes. Never read IDE private configuration or print secrets. Run live checks only with credentials provided in environment variables and distinguish executed tests from unverified gates. A mock test does not prove Atlas, Redis or Docker behavior.

If a live service or account is needed, tell the owner exactly what is needed and where it belongs, without requesting that a secret be pasted into chat. Continue independent work while waiting.

Commit coherent slices, run focused tests then one broad suite in your worktree, update `DEPLOYMENT.md` and `RELEASE_RUNTIME_DELIVERY.md`, and report hashes, commands/results and external prerequisites. Coordinate all cross-owner changes.
