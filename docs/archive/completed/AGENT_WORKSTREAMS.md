# JachAI parallel delivery prompts

Current coordination base (2026-10-06): `main` includes sandbox callback fix
`6dad794`. The existing Claude worktree is
`E:\Advance Java\jachAI-claude-delivery` on `delivery/academy-talent-ui`,
branched earlier at `c315c07`. It owns frontend and Academy/Talent workflows.
It is not merged. Inspect Git again before assigning work; do not assume this
snapshot remains current.

The prompts below are independent assignments, not proof that the listed work
is complete. Give each prompt to **one writer**. Each writer creates its own
new Git worktree/branch from the then-current `main`, never edits `main` or an
older worktree, and reports commits for review. If a worktree path already
exists, choose a new unique sibling path; never reset, force, or delete an
existing one. Agents should work continuously through their assigned scope,
not stop after a cosmetic one-file change. No agent may print, paste, read from
IDE private files, or commit secrets. Do not run Maven against another agent's
live `target/classes`.

## A. Existing Claude owner — Academy, Talent and frontend

Continue the assignment already given to the Claude agent in
`delivery/academy-talent-ui`. Ownership: `src/main/resources/templates/**`,
`src/main/resources/static/**`, frontend tests, and Java packages
`academy`, `talent`, `attempt`, `assessment`, and `question`. Do not create a
second writer for these files. Finish real learner, Academy staff, Talent
staff, and candidate journeys against actual API contracts. Verify tenant
isolation and server-authoritative scoring, not merely visual states. Commit
coherent slices, run focused tests during work and a wider suite at the end,
then provide hashes and an honest list of external/live gates. Do not merge
your branch yourself.

## B. Identity, organizations, mail and Google — paste to one agent

You own JachAI identity and communication in a NEW worktree/branch from the
latest `main`. Read `ARCHITECTURE.md`, `README.md`, `HANDOFF.md`, `DEPLOYMENT.md`,
and Git status first. Your exclusive Java packages are `auth`, `organization`,
`mail`, and `messaging`; tests for those packages are yours. Read other modules
but do not edit them or frontend. Another agent owns Academy/Talent frontend.

Finish and verify the real account path: registration -> queued verification ->
verified login -> profile/password change/reset -> Google sign-in/linking ->
workspace creation/join code/email invitation -> member role and owner
governance. Inspect existing implementations before adding anything. Fix real
logic gaps, especially retry/idempotency, token expiry, account-retention races,
mail delivery status, tenant boundaries, and failure states. Demo role accounts
must remain local-only, passwordless and unable to change/reset passwords;
ordinary users must retain password control. Email must never be marked
delivered merely because it was queued. Test wrong-user links, cross-tenant
access, revoked/expired tokens, concurrent retry, and SMTP failure recovery.

Use focused tests per slice and one broad suite at the end in YOUR worktree.
For live checks, use only an explicitly dedicated `jachai_it_*` test database
and a private SMTP/Google test account supplied through environment variables;
never touch real user data or print credentials. If live credentials are absent,
finish safe code/test work and label live checks pending. Commit each slice,
keep a branch-specific `IDENTITY_DELIVERY.md`, then report hashes and exact
unverified gates. Request cross-package changes from the coordinator rather
than editing another owner's files.

## C. AI and personal practice — paste to one agent

Create a NEW worktree/branch from current `main`; read the architecture and
current code before editing. Exclusively own Java packages `ai` and `practice`
and their tests; do not edit frontend, assessment/question, auth, billing, or
shared configuration without coordinating. Existing Gemini, Groq and Ollama
routing, Mongo-backed personal quota, free starter catalog and AI-generated
questions already exist—do not recreate them.

Make the personal practice journey dependable: 20 free generations and 20
answers per UTC day, platform-wide provider-cost cap, user-only question and
answer history, 30/90-day retention, retry-safe answers, failed-generation
refunds, validated AI output, clear provider error categories, and no copying
of private tenant questions or answer keys. AI must never determine official
assessment scores. Add focused service and real Mongo/Redis tests where safe;
exercise one live provider call only if the user supplies a key privately.
Document latency, model availability, response failures, and quota behavior
with evidence. Do not add an automatic cross-provider fallback for private
assessment drafts without explicit user approval: it sends content to a second
external service. Do not advertise personal paid practice: price, plan terms,
and checkout have not been approved or implemented.

Commit meaningful slices, run a broad suite at branch completion, write
`AI_PRACTICE_DELIVERY.md`, and report hashes plus live-provider gates. Stay in
your assigned files; request other-module changes from the coordinator.

## D. Organization sandbox billing and platform operations — paste to one agent

Create a NEW worktree/branch from current `main` (which includes `6dad794`).
Read `ARCHITECTURE.md`, `README.md`, `HANDOFF.md`, `DEPLOYMENT.md`, and current
billing/platform code. Exclusively own Java packages `billing` and `platform`
and their tests. Do not edit frontend or identity/organization modules.

Finish the organization payment and entitlement journey end to end. Both direct
bKash and SSLCOMMERZ are **sandbox only**; never add live-money hosts or real
merchant credentials. Confirm checkout creates a durable tenant-scoped order,
callbacks are independently verified server-to-server (order ID, merchant
reference, amount, BDT currency, completion/risk), duplicate/lost callbacks
cannot credit TEAM twice, admin reconciliation works, quota limits reflect the
verified plan, and buyer email queues once with honest delivery state. Review
provider availability and safe callback-origin checks. Then inspect platform
role/plan grants, support access, storage, operations, quota reconciliation,
append-only audit and ambiguous TTL cases. Fix actual defects, not merely add
classes. Test foreign-tenant denial, races and partial-write recovery. Use the
providers' official sandbox docs as the authority for API behavior.

The user currently has **no sandbox merchant credentials configured**. Do not
claim a real sandbox transaction; complete offline and opt-in tests, then list
the exact live gateway steps that require private keys. Never grant TEAM from
browser status, a fake payment, or a query reference alone. Commit each slice,
run a broad suite at the end, keep `BILLING_PLATFORM_DELIVERY.md`, report
hashes and unresolved credential gates. Cross-module changes require review.

## E. MongoDB, Redis, deployment and security runtime — paste to one agent

Create a NEW worktree/branch from current `main`. Own `config`, deployment
scripts/Dockerfile, `DEPLOYMENT.md`, and new opt-in integration tests under a
distinct `src/test/java/com/jachai/release/` package. Read all architecture
phase gates, especially section 14; do not rewrite business modules or UI.

Build a reproducible safe release harness: Java 21 build/package, Docker image
if Docker exists, liveness versus readiness, MongoDB/Redis connectivity,
indexes/TTL, tenant isolation, CAS/concurrency, outbox leases, rate-limit and
revocation failure mode, and proxy trust/forwarded-IP behavior. The test URI
must point to a disposable database named `jachai_it_*`; refuse unknown or
production databases and never delete broad data. Do not read IDE private
config or echo secrets. Run real checks only when service credentials are
available via environment variables. Clearly separate executed checks from
unverified ones; static tests do not prove Atlas/Redis/Docker behavior.

Commit coherent slices, run focused then broad tests in YOUR worktree, update
`DEPLOYMENT.md` and `RELEASE_RUNTIME_DELIVERY.md`, and report branch hashes,
commands, results, and remaining external prerequisites. Do not alter billing,
auth, Academy/Talent, AI/practice or frontend source without coordination.

## F. Final integrator and release reviewer — use only after A–E hand off

You are the final JachAI integrator, not another feature author. First inspect
the current main tree, all five delivery branches/commit hashes and dirty
worktrees. Do not reset, force, overwrite, or merge unreviewed user work.
Review each branch for scope, contracts, security and test evidence, then
integrate one reviewed slice at a time into main via non-destructive Git
operations. Resolve conflicts explicitly and ask for a decision where two
agents made incompatible product choices. Keep the architecture and handoff
files truthful.

Audit every requirement in `ARCHITECTURE.md` sections 1–14 and every open gate
in `DEPLOYMENT.md`: Academy/Talent browser journeys, personal practice, identity
and email, Google, AI, organization billing, platform administration, MongoDB,
Redis, proxy, Docker and sandbox gateway transactions. Verify roles/tenant
isolation, no answer-key leakage, server-authoritative timing/scoring/payment,
quota safety, audit repair and recovery. Run the full Java suite, browser
journeys, package build, and safe live checks that have credentials. Do not
confuse fixture/mock tests with real gateway or Atlas tests. Give a final
requirement-by-requirement PASS/PENDING/FAIL matrix with links to commits and
evidence. Never claim production ready while any required gate is pending.

## User-owned prerequisites for live gates

The agents may finish code without these, but cannot honestly verify external
services until the user configures them privately: disposable Atlas
`jachai_it_*` database URI, Redis, a verified SMTP sender, Google Web OAuth
client ID, Gemini/Groq keys (Ollama optional), bKash and SSLCOMMERZ sandbox
merchant credentials, and Docker or a deployment host. Real payment remains
out of scope. Personal paid plan pricing/terms need a separate explicit user
decision before anyone builds checkout for individuals.
