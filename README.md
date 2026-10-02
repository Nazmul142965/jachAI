# JachAI

JachAI is a Spring Boot 3 / Java 21 modular-monolith REST API for technical assessments. `ARCHITECTURE.md` is the product specification. The implementation is in progress; this is not yet a deployable SaaS release.

## Current workflows

- Email registration, verification, login, refresh rotation, access-token/refresh-session logout, and single-use password reset by email. Password reset invalidates earlier access and refresh tokens through a per-user security version.
- Organization creation and memberships; roles and tenant checks are enforced in services.
- Manual question drafts and approval, assessment publishing to immutable version snapshots, assignment, timed attempts, answer saving, deterministic scoring, manual/immediate result release, and topic-level result breakdown.
- Redis cache for immutable published assessment versions, with MongoDB fallback. Local caching is disabled until `CACHE_ENABLED=true`; production enables it by default.
- MongoDB durably records revoked access-token IDs until expiry. Redis can speed positive revocation checks with `REDIS_REVOCATION_ENABLED=true`; authentication still checks MongoDB when Redis is empty or unavailable and fails closed if MongoDB cannot confirm token state. This adds MongoDB reads per authenticated request until a safer bounded cache is designed.
- AI question drafting via Gemini (`AI_PROVIDER=google-genai`), post-attempt Academy tutoring via Groq (`AI_TUTOR_PROVIDER=groq`), or local Ollama for both (`AI_PROVIDER=ollama`). AI drafts still require human approval. AI request quota counters are in Redis; AI fails closed if Redis is unavailable.
- Academy certificate issuance after a passing, released result; learner certificate listing, staff revocation, and minimal public code verification.
- Academy instructor feedback for submitted attempts: staff save a private draft, publish after result release, and only the owning learner can read published feedback. Published feedback is immutable.
- An in-progress responsive browser dashboard for registration/login, workspaces, authoring, assignments, attempts, results, and certificates. HTML pages are rendered from `src/main/resources/templates` by a small MVC page controller; CSS/JavaScript live in `src/main/resources/static` and call the REST API.
- Talent hiring campaigns tied to a frozen assessment version, existing-member candidate invitations, staff-only candidate comparison, reviewer notes, and hold/shortlist/reject decisions.

Not yet complete: external email invitations and full frontend review queues, full Academy/Talent modules, certificate email/verification UI and audit trail, email jobs, organization-wide result-policy settings, live revocation verification, public-endpoint rate limits, Stripe billing, full integration/load testing, and deployment. Do not use the current API as a production system.

## Run locally

Use JDK 21, Maven, and a MongoDB instance. Set environment variables in your terminal or IntelliJ run configuration; Spring Boot does not load `.env` automatically. `.env.example` contains only placeholders. Never commit actual credentials.

Required: `JWT_SECRET` (at least 32 UTF-8 bytes). `MONGODB_URI` defaults to a local database under the local profile. The production profile requires `MONGODB_URI` and SMTP settings.

```powershell
$env:JWT_SECRET = '<generate-a-random-secret-of-at-least-32-bytes>'
$env:MONGODB_URI = 'mongodb://localhost:27017/jachai_local'
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

`GET /api/v1/health` checks app liveness only. `GET /actuator/health` includes infrastructure readiness when the related health indicators are enabled. To perform an opt-in MongoDB save/read/delete check, use the `local,mongo-smoke` profiles.

Open `/` for the dashboard. Spring MVC renders the HTML templates; API controllers remain REST-only. The browser keeps access/refresh tokens in memory, so a page reload signs you out. The dashboard is an early workflow UI, not a production-complete frontend.

For Redis caching, set `REDIS_HOST`, `REDIS_PORT`, and `CACHE_ENABLED=true`. For Gemini drafts, set `AI_PROVIDER=google-genai` and `GEMINI_API_KEY`. For Groq tutoring as well, set `AI_TUTOR_PROVIDER=groq` and `GROQ_API_KEY`. For local Ollama instead, set `AI_PROVIDER=ollama`, `AI_TUTOR_PROVIDER=ollama`, `OLLAMA_BASE_URL`, and `OLLAMA_MODEL`. AI operations also need Redis for quotas. Provider models and Groq base URL are configurable; see `.env.example`. Do not enable cloud billing just to run this project.

## Main API routes

All routes below start with `/api/v1`. Except registration, verification, login, refresh, and health, they require a Bearer access token. Organization routes additionally check membership and role.

| Area | Routes |
| --- | --- |
| Auth | `POST /auth/register`, `/auth/verify-email`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/request-password-reset`, `/auth/reset-password` |
| Organizations | `GET/POST /organizations`, `GET/POST /organizations/{orgId}/members`, `GET /organizations/{orgId}/members/candidates` (Talent staff) |
| Questions | `GET/POST /organizations/{orgId}/questions`, `POST .../questions/{id}/approve` |
| Assessments | `GET/POST /organizations/{orgId}/assessments`, `POST .../assessments/{id}/publish` |
| Attempts | `GET .../assignments/mine`, `GET .../assignments/{id}/attempts/mine`, `POST .../assignments/{id}/attempts`, `PUT .../attempts/{id}/answers/{questionId}`, `POST .../attempts/{id}/submit`, `GET .../attempts/{id}/result`, `POST .../attempts/{id}/release` (staff only) |
| AI | `POST /organizations/{orgId}/ai/question-drafts`, `POST /organizations/{orgId}/attempts/{id}/tutor` |
| Academy certificates | `POST /organizations/{orgId}/attempts/{id}/certificate` (staff), `GET /organizations/{orgId}/certificates/mine` (learner), `POST /organizations/{orgId}/certificates/{id}/revoke` (staff), `GET /public/certificates/{verificationCode}` (public) |
| Academy feedback | `PUT /organizations/{orgId}/academy/attempts/{id}/feedback` (staff draft), `POST .../feedback/publish` (staff), `GET .../feedback` (staff), `GET .../feedback/mine` (owning learner) |
| Talent | `GET/POST /organizations/{orgId}/talent/campaigns`, `POST .../campaigns/{id}/close`, `POST .../campaigns/{id}/invitations`, `GET .../campaigns/{id}/candidates`, `PUT .../campaigns/{id}/candidates/{candidateId}/review` |

The test profile disables MongoDB autoconfiguration and uses mocks for API smoke tests. Unit tests cover assessment/attempt rules, tenant checks, cache behavior, and AI draft/tutor boundaries. A live MongoDB/Redis/provider integration test has not yet been completed.

When creating an assessment, set `resultReleasePolicy` to `MANUAL` to keep its score, pass state, and topic breakdown hidden until an organization admin, instructor, or recruiter calls the release route. Omit it or use `IMMEDIATE` for score release on submit. The policy is frozen into the published version. Manual-policy Academy tutoring also waits for release. The result API returns `released: false` with null score/pass and empty topics while pending.

Only Academy staff can issue a certificate, and only for a passing submitted attempt whose result is released. Issuing the same attempt again returns the existing certificate. A revoked certificate remains publicly findable but verifies as invalid, and cannot be reissued for the same attempt. Public verification includes the organization and assessment names and dates, not the learner's private identity. Public verification still needs rate limiting before production use.

For feedback, copy a learner's submitted attempt ID into the Academy instructor feedback form. Save a draft, release a manual-policy result if needed, and publish. The learner opens that attempt's released result and selects “View instructor feedback.” Drafts and other learners' feedback are not readable from the learner route. A staff review queue is still needed for a smoother workflow.

Talent campaigns use the published assessment version at creation time. Closing a campaign blocks new invitations; it does not cancel existing assignments. Comparison shows each invited candidate's latest submitted attempt. A review decision is attached to one attempt and is hidden from comparison after a newer attempt until a recruiter reviews that attempt. Talent staff can page through active candidate members to choose a recipient. Invitations currently require an existing verified candidate member; external email invitations are pending.

AI provider routing is coded for Gemini question drafts, Groq tutoring, and local Ollama. AI use-case tests use mocks. No live provider call has been verified yet; real keys should only be placed in local environment settings when integration testing is ready.
