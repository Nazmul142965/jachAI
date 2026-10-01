# JachAI

JachAI is a Spring Boot 3 / Java 21 modular-monolith REST API for technical assessments. `ARCHITECTURE.md` is the product specification. The implementation is in progress; this is not yet a deployable SaaS release.

## Current workflows

- Email registration, verification, login, refresh rotation, and logout of a refresh session.
- Organization creation and memberships; roles and tenant checks are enforced in services.
- Manual question drafts and approval, assessment publishing to immutable version snapshots, assignment, timed attempts, answer saving, and deterministic scoring.
- Redis cache for immutable published assessment versions, with MongoDB fallback. Local caching is disabled until `CACHE_ENABLED=true`; production enables it by default.
- AI question drafting via Gemini (`AI_PROVIDER=google-genai`), post-attempt Academy tutoring via Groq (`AI_TUTOR_PROVIDER=groq`), or local Ollama for both (`AI_PROVIDER=ollama`). AI drafts still require human approval. AI request quota counters are in Redis; AI fails closed if Redis is unavailable.

Not yet complete: candidate/instructor frontend, certificate verification, Academy/Talent modules, password reset, email jobs, result-release policy, access-token revocation, Stripe billing, full integration/load testing, and deployment. Do not use the current API as a production system.

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

For Redis caching, set `REDIS_HOST`, `REDIS_PORT`, and `CACHE_ENABLED=true`. For Gemini drafts, set `AI_PROVIDER=google-genai` and `GEMINI_API_KEY`. For Groq tutoring as well, set `AI_TUTOR_PROVIDER=groq` and `GROQ_API_KEY`. For local Ollama instead, set `AI_PROVIDER=ollama`, `AI_TUTOR_PROVIDER=ollama`, `OLLAMA_BASE_URL`, and `OLLAMA_MODEL`. AI operations also need Redis for quotas. Provider models and Groq base URL are configurable; see `.env.example`. Do not enable cloud billing just to run this project.

## Main API routes

All routes below start with `/api/v1`. Except registration, verification, login, refresh, and health, they require a Bearer access token. Organization routes additionally check membership and role.

| Area | Routes |
| --- | --- |
| Auth | `POST /auth/register`, `/auth/verify-email`, `/auth/login`, `/auth/refresh`, `/auth/logout` |
| Organizations | `GET/POST /organizations`, `GET/POST /organizations/{orgId}/members` |
| Questions | `GET/POST /organizations/{orgId}/questions`, `POST .../questions/{id}/approve` |
| Assessments | `GET/POST /organizations/{orgId}/assessments`, `POST .../assessments/{id}/publish` |
| Attempts | `GET .../assignments/mine`, `POST .../assignments/{id}/attempts`, `PUT .../attempts/{id}/answers/{questionId}`, `POST .../attempts/{id}/submit`, `GET .../attempts/{id}/result` |
| AI | `POST /organizations/{orgId}/ai/question-drafts`, `POST /organizations/{orgId}/attempts/{id}/tutor` |

The test profile disables MongoDB autoconfiguration and uses mocks for API smoke tests. Unit tests cover assessment/attempt rules, tenant checks, cache behavior, and AI draft/tutor boundaries. A live MongoDB/Redis/provider integration test has not yet been completed.
