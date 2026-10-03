# JachAI — Product Vision and Architecture

Status: Working specification for implementation. Update this file when a product decision changes.

## 1. Product vision

JachAI is a multi-tenant SaaS platform for technical assessment and skill verification. One assessment engine serves two products:

- **JachAI Academy** helps training institutes and bootcamps assess learners, identify weak topics, provide instructor feedback and AI tutoring, and issue verifiable certificates.
- **JachAI Talent** helps companies invite candidates to role-specific assessments, compare results, review attempt integrity signals, and make shortlist decisions.

An organization buys access to one or both modules. Each organization has its own users, assessments, attempts, reports, and subscription entitlements. The platform runs as one Spring Boot application with clearly separated domain modules; microservices are out of scope.

The product's central promise is a trustworthy assessment lifecycle: create, publish, invite, attempt, score, review, report, and verify. AI assists people within that lifecycle; it does not control official scores or publish unreviewed questions.

## 2. Product boundaries

### Shared core

Organization workspaces, membership and roles, question bank, assessment versions, invitations and assignments, timed attempts, deterministic scoring, topic-level analytics, integrity telemetry, audit history, notifications, subscription entitlements, and REST APIs.

### Academy module

Courses and batches, learner assignments, progress reports, instructor feedback, instructor–learner messaging, AI Tutor after an attempt, and certificate issuance and public verification.

### Talent module

Hiring campaigns, recruiter invitations, candidate comparison, reviewer notes, integrity review, shortlist decisions, and candidate reports. Certificates are optional here and are not the primary outcome.

### Explicitly outside the first release

Live code execution, video or face proctoring, automated cheating verdicts, advanced live chat infrastructure, and a full applicant tracking system. These require substantial additional security and product work.

## 3. Users and permissions

Roles are scoped to an organization, except for platform administration. A person may belong to more than one organization with different roles.

| Role | Scope | Main capabilities |
| --- | --- | --- |
| `PLATFORM_ADMIN` | Entire JachAI platform | Manage plans, support operations, and platform health; access to tenant content should be exceptional and audited. |
| `ORG_ADMIN` | One organization | Manage workspace, memberships, module access, billing, and organization settings. |
| `INSTRUCTOR` | Academy organization | Manage question drafts and assessments; assign learners; review results and provide feedback. |
| `LEARNER` | Academy organization | Take assigned assessments, view released results, use AI Tutor, and view certificates. |
| `RECRUITER` | Talent organization | Manage campaigns and assessments, invite candidates, review results, and shortlist. |
| `CANDIDATE` | Talent organization | Take invited assessments and view only the results released to them. |

Prefer a `Membership` document (`userId`, `organizationId`, role, status) over one global role stored on `User`. Every authorization decision must consider both role and active organization.

## 4. Technical architecture

- Java 21 and Spring Boot 3.x.
- Spring Web REST API, Spring Security, Bean Validation, Spring Data MongoDB, Spring Data Redis, Spring Mail, and Spring AI.
- MongoDB Atlas for durable product data. Local MongoDB may be used for development or tests.
- Redis for short-lived cache entries, rate limits, one-time coordination where needed, and revoked access-token IDs. MongoDB remains the source of truth.
- A responsive HTML/CSS/Bootstrap/JavaScript frontend that calls the REST API. It may initially be served from Spring Boot's static resources on the same origin. The API must not depend on server-rendered pages.
- SMTP or a transactional email provider behind an `EmailSender` interface. Provider credentials come from environment variables.
- Direct bKash and SSLCOMMERZ sandbox payment adapters for plan purchases; access decisions come from server-verified transactions, not from browser redirects.

Use a modular monolith and package by domain. Controllers call services; services enforce business rules and use repositories. DTOs (prefer Java records where appropriate) cross API boundaries. Mongo documents never serve directly as public responses.

```text
com.jachai
  shared/        configuration, errors, tenant context, audit, email, utilities
  auth/          registration, email verification, login, refresh, password reset
  organization/  workspaces, membership, roles, invitations
  question/      question bank, topics, AI question drafts
  assessment/    assessment authoring, publishing, immutable versions
  attempt/       timed sessions, answers, scoring, telemetry
  academy/       courses, batches, learner feedback, tutor, certificates
  talent/        campaigns, candidate review, comparison, shortlist
  messaging/     instructor–learner conversations
  billing/       plans, entitlements, payment orders, provider adapters
  ai/            provider adapter, prompts, limits, usage records
```

Within each module, use `controller`, `service`, `repository`, `model`, and `dto` folders only where they improve clarity. Avoid creating empty packages in advance.

## 5. Tenant isolation: mandatory rule

Every organization-owned document contains `organizationId`. The authenticated request resolves an active organization from a verified membership. Services and repositories include that organization ID in every read, update, and delete. A client-supplied `organizationId` never grants access by itself.

For example, fetch an assessment with `findByIdAndOrganizationId(id, activeOrganizationId)`, not `findById(id)`. Check the same rule for nested resources such as answers, messages, reports, and certificate administration. Platform-public endpoints, such as certificate verification, expose only a deliberately limited public view.

Use compound indexes for frequent tenant queries and uniqueness rules (for example, organization plus assessment slug). Write tests where organization A cannot read or mutate organization B's data.

## 6. Core data model

| Document | Purpose and important fields |
| --- | --- |
| `User` | Global identity, email, password hash, email verification state, account status. |
| `Organization` | Tenant name, type (`ACADEMY` or `TALENT`), settings, status. A tenant may later enable both modules. |
| `Membership` | User, organization, role, status, invitation metadata. |
| `Question` | Tenant, topic, type, difficulty, content, answer/rubric, draft/review status. Keep answers out of candidate APIs. |
| `Assessment` | Tenant, owner, title, configuration, draft/published state, current version. |
| `AssessmentVersion` | Immutable published instructions, duration, passing rules, selected question snapshots, grading rules. |
| `Assignment` / `Invitation` | Recipient or invite token, assessment version, availability window, attempt limit, status. |
| `Attempt` | Tenant, candidate, assessment version, server start/deadline, state, answer snapshots, score breakdown, timestamps. |
| `TelemetryEvent` | Tenant, attempt, event type, server receipt time, client context, severity hint. |
| `Certificate` | Tenant, learner, attempt, assessment version, unique verification code, issue/revocation state. |
| `Course` / `Batch` | Academy grouping for learning and reporting. |
| `HiringCampaign` / `ReviewDecision` | Talent grouping, reviewer notes, shortlist outcome. |
| `Conversation` / `ChatMessage` | Academy instructor–learner messages, tenant-scoped and access-controlled. |
| `Subscription` / `Plan` | Tenant billing state and feature entitlements. |
| `EmailJob` | Template, recipient, delivery state, retry count; never store provider credentials. |
| `AuditEvent` | Actor, tenant, action, target, timestamp, useful change metadata. |

Keep documents bounded. For long-running attempts or high-volume telemetry, store events separately rather than growing one Mongo document without limit. Create indexes for email, membership lookups, tenant-scoped listings, invitation tokens, certificate codes, and attempt deadlines. Use unique indexes where uniqueness is a business rule.

## 7. Assessment lifecycle and scoring

1. Instructor or recruiter drafts questions. AI-generated questions remain drafts until a human reviews them.
2. An assessment is configured and published as an immutable `AssessmentVersion`. Editing later creates a new version.
3. A learner/candidate receives an assignment or invitation. The server validates availability, membership/invite, attempt limit, and module entitlement.
4. The server starts an attempt and records the deadline. The client timer is visual only; the server decides whether an answer is on time.
5. Answers are saved with validation and an attempt state check. Submitting is idempotent and cannot change a finalized result.
6. Objective questions are scored by deterministic rules against the published version. Manual-review questions enter a review queue. AI may suggest feedback but cannot silently set official scores.
7. Result release follows organization policy. Topic-level breakdown and weak areas use the versioned question/topic data.
8. Academy certificates are issued only after the required pass/review conditions. Verification shows status and minimal public details.

For the first release, support single-choice and multiple-choice questions. Add short-answer manual review after the core attempt flow works. Do not implement an unsafe code runner inside the API server.

Telemetry records tab visibility changes, fullscreen exits, copy/paste attempts, suspicious timing, and optional IP/user-agent signals. It is evidence for human review, not proof of cheating. Avoid collecting unnecessary personal data.

## 8. Authentication and security

- Registration requires email verification before normal access. Use a random, single-use, expiring verification token; store only its hash in MongoDB or Redis.
- Optional Google Identity Services sign-in verifies ID-token signature, audience, issuer, expiry, subject, and verified email server-side. Use the stable Google subject as the external identity; never silently merge an existing password account by email. Require authenticated same-email linking. A third-party email that Google does not authoritatively host must complete JachAI's own email verification before linking. Google login returns ordinary JachAI access/refresh tokens; organization authorization rules do not change.
- Passwords use an adaptive hash such as BCrypt. Add password-reset tokens with the same single-use and expiry rules.
- REST API authentication uses short-lived JWT access tokens and rotated refresh tokens. Bind refresh sessions to a user/device record; revoke on logout or security events.
- Redis stores revoked access-token `jti` values until their original expiry when immediate logout is required. A missing Redis connection must not silently bypass an enforced revocation check.
- Validate JWT issuer, signature, expiry, and token purpose. Never trust a role or tenant ID merely because it appears in a client request.
- Apply endpoint authorization and service-level ownership checks. Rate-limit login, reset, invite, AI, and public verification endpoints.
- Keep secrets out of the repository and logs. Use environment variables and secret management in deployment.
- Audit role changes, assessment publishing, score overrides, certificate actions, and billing transitions.

## 9. AI design

Expose AI through a small internal interface such as `AiService`, with provider-specific configuration. Select a provider per use case, not per individual request:

- Local development: Ollama can handle both use cases with `OLLAMA_BASE_URL` and an explicitly selected model.
- Cloud demo: Gemini handles instructor question drafts; Groq handles short Academy tutor explanations. Official scoring never calls either model.

Use provider-specific variables such as `GROQ_API_KEY` and `GEMINI_API_KEY`; an Ollama endpoint uses a base URL. Start with two bounded use cases: instructor question drafting and post-attempt AI Tutor explanations. AI Tutor must not reveal answers during an active assessment. Include usage limits per organization, input/output length limits, timeouts, logging without secrets, and a graceful unavailable state. Validate structured question output before saving it as a draft. Never place AI-generated questions directly into a published assessment. The Atlas Free cluster is a hard 0.5 GB limit including indexes: do not persist full AI request/response logs by default, keep any future tutor history short with TTL, and monitor logical storage before it approaches the limit. No paid cloud tier is enabled by application code.

## 10. Email and messaging

Email is a product feature, not only a registration detail. Initial templates: verify email and reset password. Later: organization invitation, assessment invitation/reminder, result released, certificate issued, and billing notices.

Implement `EmailSender` so a local test sender and a production provider can be swapped. Create email jobs when an event occurs, then send asynchronously with retries and delivery status. Never use email delivery success as the sole source of truth for an assessment or payment state.

Teacher–student messaging belongs to Academy. Store conversations/messages in MongoDB, require tenant and participant access checks, and start with REST endpoints plus periodic frontend refresh. WebSocket can be added when real-time delivery is actually needed. AI Tutor conversations are separate from human messaging and have their own usage and retention rules.

## 11. Billing and entitlements

The product can begin with manually assigned plans so the assessment engine can be completed first. Plans grant explicit features and quotas: Academy, Talent, AI usage, active assessments, invitations, certificate issuance, and retention. Check entitlements on the server, not just in the UI.

Use both direct bKash sandbox and SSLCOMMERZ sandbox for the first billing release; do not enable either live environment by application configuration yet. Keep a provider-neutral payment order/attempt history with organization, amount in BDT, provider, unique merchant transaction ID, gateway reference, status, and timestamps. Each gateway must verify the payment server-to-server against its own validation/query API, including amount, currency, merchant transaction ID, and success state. Treat notifications and browser redirects as untrusted until verification; process duplicate callbacks idempotently. Grant or renew TEAM for a bounded paid period only after verified payment, and let expiry fall back to FREE. Recurring automatic charging is out of scope until each provider's contractual/API support is established; offer explicit manual renewal first. SSLCOMMERZ may itself offer bKash, so the frontend must distinguish direct bKash from the aggregator's other payment methods and not mislabel duplicate choices.

## 12. Configuration

Read connection strings and secrets from environment variables. Provide an `.env.example` containing names and non-secret examples only; do not commit a real `.env` file.

| Variable | Use |
| --- | --- |
| `MONGODB_URI` | MongoDB Atlas or local connection URI, including database name. |
| `REDIS_HOST`, `REDIS_PORT` | Redis connection for local or cloud deployment; add username/password/TLS variables as required by the chosen host. |
| `JWT_SECRET` | Strong signing secret for the selected HMAC algorithm. |
| `PLATFORM_ADMIN_USER_IDS` | Optional comma-separated IDs of existing verified users authorized for platform metadata endpoints; empty by default. |
| `AI_PROVIDER` | `none`, `ollama`, or `google-genai` for question drafts; `ollama` also supports local tutor testing. |
| `AI_TUTOR_PROVIDER` | Optional tutor override: `groq` for cloud demo, or `ollama` locally. |
| `OLLAMA_BASE_URL`, `OLLAMA_MODEL` | Local AI configuration. |
| `GROQ_API_KEY`, `GROQ_BASE_URL`, `GROQ_MODEL` | Groq configuration when selected. |
| `GEMINI_API_KEY`, `GEMINI_MODEL` | Gemini configuration when selected. |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | SMTP configuration when mail delivery is enabled. |
| `BKASH_SANDBOX_*`, `SSLCOMMERZ_SANDBOX_*` | Separate sandbox merchant credentials for the two Bangladesh payment gateways when enabled. Never place live credentials in the initial release. |
| `GOOGLE_CLIENT_ID` | Optional Google Identity Services web client ID for verified Google sign-in; no Google client secret is needed for this ID-token flow. |
| `APP_PUBLIC_BASE_URL` | Origin used in email and certificate links. |

Use local, test, and production profiles for behavior and infrastructure settings. Fail startup for missing secrets required by an enabled feature; allow disabled future features to remain unconfigured. Do not hardcode Atlas, Redis, SMTP, AI, or payment credentials.

## 13. API and frontend conventions

Use versioned endpoints under `/api/v1`. A representative shape is:

```text
POST   /api/v1/auth/register
POST   /api/v1/auth/verify-email
POST   /api/v1/auth/login
POST   /api/v1/auth/google
POST   /api/v1/auth/refresh
POST   /api/v1/organizations
GET    /api/v1/organizations/{orgId}/assessments
POST   /api/v1/organizations/{orgId}/assessments
POST   /api/v1/organizations/{orgId}/assessments/{id}/publish
POST   /api/v1/attempts/{assignmentId}/start
PUT    /api/v1/attempts/{attemptId}/answers/{questionId}
POST   /api/v1/attempts/{attemptId}/submit
GET    /api/v1/attempts/{attemptId}/result
GET    /api/v1/public/certificates/{verificationCode}
POST   /api/v1/organizations/{orgId}/billing/orders
POST   /api/v1/billing/bkash/callback
POST   /api/v1/billing/sslcommerz/ipn
```

The organization path is context, not authorization: verify membership and scope every query. Use validation, consistent error responses, pagination for lists, and server-generated timestamps. The frontend should consume the same API that an external client could use. Same-origin static pages are acceptable initially and simplify the first deployment.

## 14. Build sequence and acceptance gates

1. **Foundation:** Inspect existing code; establish package layout, profiles, Mongo connection, validation, global errors, health check, Git ignore rules, and test setup. Prove a small Mongo save/read works in a non-production environment.
2. **Identity and tenants:** Register, email verification, login/refresh/logout, organizations, memberships, roles, and tenant isolation tests.
3. **Assessment engine:** Question bank, draft/publish flow, immutable versions, assignment/invitation, timed attempt, answer saving, deterministic scoring, result release.
4. **Academy:** Courses/batches, weak-topic report, feedback, certificate issue/verify, instructor–learner messaging, AI Tutor.
5. **Talent:** Campaigns, candidate invites, review notes, comparison, shortlist, integrity review.
6. **SaaS operations:** Redis cache/rate limits/revocation, plan entitlements, direct bKash and SSLCOMMERZ sandbox billing, Google login, email expansion, deployment, observability.

At each phase, implement one vertical workflow end to end, add tests for business rules and cross-tenant access, run the relevant build/tests, and document how to try it. Do not claim completion from a successful application startup alone.

## 15. Engineering rules for implementation agents

- Inspect the repository and any `AGENTS.md` before changing code. Preserve existing user work and conventions unless there is a clear reason to change them.
- Keep the single-application architecture and REST-first boundary.
- Never expose answer keys in candidate-facing payloads before result release.
- Never rely on the browser timer, browser role checks, or browser payment status for server decisions.
- Keep current work small enough to compile and verify. Explain changed files, tests, and unresolved dependencies at handoff.
- Keep configuration examples free of real secrets. Do not print credentials or connection URIs in logs or chat.
- Record new product decisions in this document so future agents have durable context.

## Phase 1 implementation decisions

- Foundation code lives under `shared`; other domain packages appear when their first workflow is implemented.
- `/api/v1/health` is application liveness. Actuator health includes MongoDB readiness; the local `mongo-smoke` profile performs an opt-in temporary save/read/delete check.
- Until identity and tenant authorization are implemented in Phase 2, all API paths except health are closed. No temporary application user or password is created.

## Frontend implementation decision

- Browser HTML belongs in `src/main/resources/templates` and is served by a small Spring MVC page controller using Thymeleaf, per user preference. CSS and JavaScript belong in `src/main/resources/static`. Page controllers render only the shell and email-action pages; business data is fetched through the same `/api/v1` REST API used by external clients.

## Phase 3 implementation decisions

- Each assessment has a result-release policy (`IMMEDIATE` by default for compatibility, or `MANUAL`) frozen in its published version. Under `MANUAL`, submit is still final and server-scored, but candidate-facing score, pass state, and topic breakdown remain hidden until staff explicitly releases that attempt. Academy tutoring also waits for manual release. A later organization-wide policy setting may override the authoring default; changing existing published versions is not allowed.

## Phase 4 implementation decisions

- An Academy instructor or organization admin issues one certificate per passing, released attempt. Issue is idempotent; revocation is terminal for that attempt, so it cannot mint a fresh valid code afterward. Learners can list their own certificates. Public verification exposes organization/assessment names and issue/revocation dates, not learner identifiers. New certificates record issuing/revoking actor IDs and generate deterministic append-only audit events. A failed audit insert after the certificate write is repaired by retrying the operation; pre-audit records cannot recover unknown historical actors. Staff-only tenant-scoped audit history is paged. Email delivery, a polished public verification page, and live MongoDB audit-failure verification remain required before production.

## Phase 5 implementation decisions

- A Talent hiring campaign freezes the selected published assessment version. Existing verified candidates can be invited through a campaign assignment; Talent staff can list active candidate members without gaining access to all memberships. Closing a campaign prevents new invitations but leaves already-issued assignments usable until their own availability windows end. Comparison and review are staff-only and tenant-scoped. A shortlist/hold/reject decision is attached to a specific submitted attempt; after a newer attempt, the old decision is not shown as current. Each saved decision has a staff-only audit entry recording actor, attempt, decision, note, and time; a deterministic event reference on the decision allows retry repair without duplicate history. Live MongoDB failure/retry verification remains open.

## Talent email invitation implementation decision

- Reissuing an existing email invitation rotates its link and expiry but preserves `createdAt`, because the monthly invitation quota is charged only for the first insert. Existing records whose creation timestamp was already overwritten cannot be reconstructed from the current document alone and need a migration audit before historical quota reconciliation.

- Recruiters may invite an arbitrary email to an open campaign. An opaque 256-bit token is queued through `EmailSender`; only its SHA-256 hash is stored in a tenant-scoped invitation document. The queued email link is encrypted at rest. It expires after at most 14 days, never later than the availability end, and is removed by TTL. A signed-in, verified user whose account email matches the invitation accepts it, gaining an active `CANDIDATE` membership and deterministic campaign assignment. A pending invitation can be resent with the same window, rotating its token. Acceptance is idempotent after partial writes or concurrent requests, though it still needs live Mongo concurrency verification. Rate limiting, SMTP verification, and a richer invitation status UI remain required before production.

## Email outbox implementation decision

- Registration verification, password reset, and Talent invitation links enqueue an `email_jobs` document. The payload is AES-256-GCM encrypted with a random nonce and job ID as associated data. A separate stable `MAIL_JOB_ENCRYPTION_KEY` is preferred; absent that, a domain-separated key is derived from `JWT_SECRET`. A scheduled MongoDB worker atomically leases due jobs, verifies that link tokens are still current, sends through the local or SMTP transport, and retries with bounded backoff. It never sends a link past its validity deadline. After success, expiry, or five failed attempts, ciphertext and nonce are removed; job metadata expires after 30 days. Delivery is at-least-once, not exactly once, because a worker may crash after SMTP accepts a message. Live MongoDB/SMTP, lease recovery, and free-tier storage behavior remain to be verified.

## Request rate-limit implementation decision

- A Redis Lua counter enforces fixed windows atomically for auth/email abuse, invitation creation, AI bursts, and public certificate lookups. Keys HMAC the IP/email/organization-user scope with the application secret; raw personal identifiers are not placed in Redis keys. Production enables enforcement and fails closed with 503 when Redis cannot confirm a count; local development can keep it off until Redis is available. Authentication limits apply at both IP and normalized-email level, whereas invite and AI burst limits apply per organization and actor. The socket remote address is used rather than trusting client-supplied forwarding headers. Trusted reverse-proxy configuration and live Redis behavior must be verified before deployment.

## Plan entitlement implementation decision

- A `Subscription` document is keyed by organization and records `FREE` or `TEAM` tier plus billing state. Missing, inactive, or expired TEAM state resolves to FREE. Both organization types can use FREE so the product remains testable without payment. A plan snapshot explicitly states module access, AI use, published assessment count, invitation count, certificate count, and retention. The server reserves MongoDB `entitlement_usage` slots atomically before publishing an assessment, creating an invitation/assignment (including batch distribution), or issuing a certificate; handled write failures refund slots. AI daily counters use the plan's limits in Redis. Reconciliation repairs provable gaps, but historical migration, ambiguous expired invitation evidence, and live concurrency/TTL behavior remain open. Payment verification, callbacks, manual renewal, and admin reconciliation (including SSLCOMMERZ merchant-transaction lookup after a lost callback) are implemented against sandbox APIs but have not been verified with live sandbox credentials.

## Talent integrity telemetry implementation decision

- The active candidate may record tab visibility, optional fullscreen exit, copy/paste, and answer-save events for their own campaign attempt. The server stamps receipt time and verifies candidate, tenant, campaign assignment, status, and deadline. Events live in a separate `attempt_telemetry` collection with tenant/attempt/time index and 90-day TTL; a 200-unexpired-event count guard limits growth but is not an atomic hard cap. Talent staff may inspect a submitted attempt's summary and paged events. Rapid answer intervals are calculated only from client-reported answer-save events. Nothing here blocks the attempt or produces an automated cheating verdict. IP, user agent, clipboard contents, and keystrokes are not stored. Signals are untrusted and require human interpretation; live TTL/index and browser behavior remain unverified.

## Academy feedback implementation decision

- One instructor feedback document is scoped to an Academy organization and submitted attempt. Staff can revise a private draft; publishing requires the result to be released and makes the text immutable. Only the attempt owner can read published feedback. This is narrative guidance, not an AI-generated score or a score override. Result-release review queue now exists; feedback notifications and audit events remain separate work.

## Academy course and batch implementation decision

- Courses are unique by normalized name within an Academy organization; batches are unique by normalized name within a course. A batch has a start/end window. Staff enroll only verified, active learner members; repeat enrollment is idempotent. Enrollment stores immutable course/batch display names so learner listing needs one tenant-scoped query. Renaming is not supported yet. Batch assessment assignments, per-learner progress, and staff-only aggregate cohort analytics now build on this grouping; live MongoDB verification remains open.

## Academy batch assessment implementation decision

- A batch assessment freezes one published assessment version and an availability window within the batch dates. Its per-learner `Assignment` IDs are deterministic and retain a `batchAssessmentId` for tenant-scoped reporting. Existing enrollments are distributed on creation; later enrollments are synchronized. MongoDB transactions are not required for the first release, so a distribution interruption may leave a parent record with some missing assignments; a staff-only idempotent reconcile endpoint repairs it. The progress page shows each learner's latest submitted attempt, including staff-visible score before candidate result release. Cohort analytics aggregate the latest submitted attempt per assignment in MongoDB and compare assignment coverage to active enrollment; completion uses active learners as denominator, while pass rate uses only learners whose latest submitted attempt is fully graded. Pending manual grades are reported separately and excluded from the score average. Full UI pagination and live aggregation verification remain required work.

## Academy direct messaging implementation decision

- One `Conversation` document represents an instructor–learner pair within one Academy tenant; messages are separate documents indexed by tenant, conversation, and send time. Only active participants can read/send. Message bodies are limited to 1,000 characters, a 500-stored-message guard reduces accidental free-tier growth, and TTL removes messages approximately 90 days after sending. The count guard is not an atomic quota under concurrency; production rate limits and storage monitoring are still needed. The browser polls an open conversation every 15 seconds rather than introducing WebSocket infrastructure. AI Tutor responses are separate and not persisted as human chat history.

## Staff result-review queue implementation decision

- Staff see a tenant-scoped, newest-first page of submitted attempts awaiting manual result release. This is a workflow queue, not a second scoring mechanism; official scoring remains deterministic at submission and candidates still receive no score until release. Queue payloads contain the staff-visible score and participant ID but never saved answers or answer keys. A separate staff-only submitted-attempt review route exposes frozen question snapshots, selected answers, and correct options so instructors/recruiters can inspect before release; candidate routes remain answer-key-free. A compound MongoDB index supports the pending query. Live index/query and browser verification remain open.

## Organization membership governance implementation decision

- The organization creator starts as owner. The current owner is always an effective `ORG_ADMIN` and cannot be demoted or deactivated through membership endpoints. A non-owner `ORG_ADMIN` may manage regular Academy/Talent roles but may not grant, demote, deactivate, or reactivate another `ORG_ADMIN`. No admin can change or deactivate their own membership. `PLATFORM_ADMIN` is separate from organization roles; its first read-only boundary is implemented, but the broader platform role is incomplete. No generic moderator/sub-admin role is introduced without a permission definition.
- Platform administration is bootstrapped from an environment-controlled allowlist of verified, enabled user IDs. A current platform administrator may grant/revoke the role for another existing user; grants require a verified, enabled target, self-change and API mutation of bootstrap admins are forbidden. The append-only `platform_role_events` collection is both the role source of truth and its audit history, with a unique per-user revision to reject concurrent changes. It is distinct from organization membership and has read-only organization metadata listing, exact-ID user lookup, and tenant-scoped billing subscription/order summaries for support; the latter exclude gateway identifiers and cannot initiate or reconcile payment. A platform admin can preview and repair provable entitlement-usage gaps for an existing tenant without becoming its ORG_ADMIN; bucket changes stamp the actor and use compare-and-set. Ambiguous/TTL-erased evidence remains for manual review. It cannot read tenant assessments/answers or change subscriptions. Live MongoDB index/concurrency tests, plan mutation, and exceptional audited tenant-content access remain unimplemented.
- `PUT /organizations/{orgId}/members/{userId}/role` changes an active member's role; `DELETE /organizations/{orgId}/members/{userId}` deactivates membership; adding an inactive former member reactivates the existing record. MongoDB updates condition on tenant, user, active state, and previous role, so conflicting changes fail instead of silently overwriting. JWTs contain no organization role; service authorization reads active membership on each request, so a changed role applies without waiting for token expiry. Backend checks are authoritative; frontend controls only aid navigation.
- Each membership transition stores actor, prior role/state, timestamp, and an event ID on the member, then inserts an append-only audit event. Retrying a no-op role change/deactivation or opening member history can repair an audit insert failure. Staff can page through a tenant-scoped member history. This is not a cross-document transaction: a crash followed by another mutation before audit repair could lose the earlier event. Live MongoDB concurrency and failure tests and a reconciliation job remain required before production.
- Only the current owner may transfer ownership, and only to another verified, active organization admin. The owner change uses a MongoDB compare-and-swap on the previous owner ID; its last event metadata supports append-only audit repair when ownership history is read. The previous owner remains an `ORG_ADMIN` until the new owner changes that role. To preserve access through a concurrent membership change, the organization document is authoritative for owner admin permission and workspace listing even if the owner's membership record is stale. This cross-document race, live audit delivery, and browser flow still need real MongoDB verification; two-person acceptance/re-authentication is not implemented.

## Organization invitation implementation decision

- An `ORG_ADMIN` may invite a not-yet-registered email to an Academy or Talent workspace for a role valid in that workspace. Only the current owner may invite or revoke an `ORG_ADMIN` invitation. Links use a random 256-bit token; MongoDB stores only its SHA-256 hash, and the encrypted mail outbox holds the link until delivery. Reissuing an invitation rotates the token but keeps its original creation time. The link expires after seven days, and MongoDB TTL eventually removes the invitation document, so the paged list is not a permanent audit ledger.
- A signed-in recipient must have the exact verified, enabled account email to accept. Acceptance uses optimistic document versioning so a concurrent revocation or token rotation rejects the stale save. Accepted invitations cannot be reissued, and their TTL is extended to seven days after acceptance for membership-repair retries. A durable accepted invitation authorizes the verified recipient to create or confirm the matching active membership without depending on the inviter retaining admin privileges; the recipient is recorded as the membership-change actor. An inactive membership cannot be reactivated by an accepted link, even one the recipient previously used; only an admin may reactivate it through member management. MongoDB does not provide a cross-document transaction here: a failure after recording acceptance but before membership creation requires a retry with the same link while the record still exists. Live Atlas concurrency, failure-recovery, and SMTP delivery remain unverified.

## Organization result-release default implementation decision

- An organization admin sets a tenant-scoped default of `IMMEDIATE` or `MANUAL`; absent settings read as `IMMEDIATE` without creating a document. Assessment authors may override the default at draft creation. The resolved policy is stored on the assessment and frozen into each published version, so later setting changes never retroactively release or conceal past results. Settings use optimistic versioning to reject concurrent updates; live MongoDB behavior remains to be verified.

## Platform quota repair audit

- Platform quota repair records an append-only `REQUESTED` event before mutation and a `COMPLETED` event afterward; an incomplete or unconfirmed run requires a fresh report. The audit history is scoped to the tenant and available only to platform admins. Live MongoDB verification of this cross-document failure mode remains open.

## Frontend pagination implementation decision

- Campaign lists, candidate comparison, Academy catalog/batch/enrollment/assignment lists, Academy batch-progress rows, messaging partners/conversations/messages, and sandbox payment history follow the server's 20-row pages. Source review also found page controls on the other paged dashboard lists; this still needs browser verification with more than 20 records and empty/error pages. Message polling runs only on the newest page. Cohort analytics are fetched once when staff open progress, not on every progress-page change. Page controls do not replace tenant authorization on the server.

## Deployment and health-probe implementation decision

- The multi-stage Dockerfile packages the Java 21 modular monolith into an unprivileged JRE runtime image. In the production profile, Actuator readiness includes application readiness state, MongoDB, and Redis; liveness remains independent of those external systems. Only status is exposed, not details. Local full tests and executable JAR packaging pass; Docker image/run and live dependency probes remain unverified because Docker and service credentials are not available in this environment. `DEPLOYMENT.md` lists the sandbox-only rollout gates.
- When an attempt's result is released (immediately on submission or later by staff), queue one result-available email keyed by attempt ID. The message only links to sign-in/My assessments, never includes a score or answer key. Outbox delivery rechecks the released attempt and its user's current address. A retry of submission or staff release can repair an enqueue failure without changing official result state or duplicating a sent message. Local tests cover these paths; live MongoDB/SMTP delivery remains unverified.
