# JachAI pre-deployment release check

**Revision:** `9006f69` on `main`
**Checked:** 2026-10-09
**Local URL:** `http://127.0.0.1:8080/`
**Data safety:** no customer data was deleted or modified; no real-money checkout was started.

## Result

| Area | Status | Evidence |
| --- | --- | --- |
| Java build and tests | **PASS** | `.\mvnw.cmd -q test`; 660 tests, 0 failures, 0 errors. |
| Frontend journeys | **PASS** | `node src\test\frontend\run-journeys.js`; 151/151 checks across six journeys. `node --check` and `git diff --check` also passed. |
| Port 8080 health | **PASS** | Existing listener responded with HTTP 200 from `/api/v1/health`; six roles were returned by `/api/v1/demo/accounts`. |
| Six demo roles in real Chrome | **PASS** | Clicked all six live buttons: Academy owner/instructor/learner and Talent owner/recruiter/candidate. Each reached the expected workspace and role-specific navigation. Academy learner exposed Tutor/Messages; Talent candidate exposed Tutor; staff did not. |
| Demo restrictions and Support UI | **PASS** | Real Chrome demo learner showed `No password set` and hid password-change form; Support opened with its ticket form. Demo IDs remain protected in `AuthService` and unit tests. |
| Academy/Talent navigation | **PASS** | Live Chrome role smoke covered both workspace types and role-scoped navigation; mocked browser suite covers deeper Academy/Talent flows, billing disclosures, reconciliation, and platform screens. |
| Real-user password change | **UNVERIFIED** | No non-demo account credential was supplied for a safe live mutation. Auth password-change unit tests passed. |
| Google sign-in/linking | **UNVERIFIED** | No Google client/ID token and no configured live OAuth origin were available. Google service/verifier tests passed. |
| Platform-admin live browser path | **UNVERIFIED** | No platform-admin credential was supplied; mocked platform-admin journey passed. |
| MongoDB Atlas / Redis readiness | **UNVERIFIED** | The local process was healthy, but no production Atlas/Redis integration check was run and no customer database was touched. |
| SMTP delivery | **UNVERIFIED** | Tests used local/test transports; no inbox delivery or final `email_jobs` state was verified. |
| AI provider | **UNVERIFIED** | No live Gemini/Groq/Ollama request was made. |
| bKash/SSLCOMMERZ sandbox | **UNVERIFIED** | No merchant credentials or real sandbox transaction/callback was used. |
| Hosted HTTPS deployment | **UNVERIFIED** | No public production URL, reverse proxy, OAuth callback, or hosted browser run was available. |

## Deployment decisions still required

1. Provide the production `MONGODB_URI`, database name, JWT secret, Redis connection, SMTP provider credentials, and stable HTTPS `APP_PUBLIC_BASE_URL` through a secret manager; never commit them.
2. If public demo access is wanted, set `DEMO_PUBLIC_ACCESS=true` and use HTTPS. `DEMO_SHARED_DATABASE` is **not required** and must not be used to restore a database-name restriction.
3. Decide on a host whose outbound mail policy permits the chosen SMTP/API transport, then verify an actual verification/reset/invitation inbox and the resulting `email_jobs` state.
4. Supply controlled test credentials or a disposable test account for one live real-user password-change flow, one platform-admin flow, and Google OAuth verification.
5. Supply only bKash/SSLCOMMERZ **sandbox** credentials and a public HTTPS callback URL before testing payment verification. Live payment credentials must remain disabled.
6. Run the opt-in MongoDB/Redis integration and reverse-proxy client-IP checks against disposable infrastructure before routing customer traffic.

**Release recommendation:** application code is test-clean and local demo-safe, but production deployment should remain gated until the items marked **UNVERIFIED** that are required for the chosen hosting environment are completed.

## Deployment environment and smoke checklist

Configure these names through the hosting platform's secret manager or environment
settings; never print their values or commit them:

| Purpose | Environment variable |
| --- | --- |
| MongoDB connection | `MONGODB_URI` |
| MongoDB database | `MONGODB_DATABASE` |
| JWT signing secret | `JWT_SECRET` |
| Redis host/port or URL | `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED`, or `SPRING_DATA_REDIS_URL` |
| SMTP transport | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` |
| Mail payload encryption | `MAIL_JOB_ENCRYPTION_KEY` |
| Public HTTPS origin | `APP_PUBLIC_BASE_URL` |
| Public demo access | `DEMO_PUBLIC_ACCESS` |
| Google sign-in | `GOOGLE_CLIENT_ID` |
| AI providers | `AI_PROVIDER`, `AI_TUTOR_PROVIDER`, `GEMINI_API_KEY`, `GROQ_API_KEY`, `OLLAMA_BASE_URL` |
| Payment sandboxes | `SSLCOMMERZ_SANDBOX_STORE_ID`, `SSLCOMMERZ_SANDBOX_STORE_PASSWORD`, `BKASH_SANDBOX_APP_KEY`, `BKASH_SANDBOX_APP_SECRET`, `BKASH_SANDBOX_USERNAME`, `BKASH_SANDBOX_PASSWORD` |
| Trusted proxy (only when required) | `TRUSTED_PROXY_CIDRS` |

Before routing users, check from the deployed HTTPS origin:

1. `GET /api/v1/health` returns HTTP 200.
2. `GET /actuator/health/liveness` returns HTTP 200.
3. `GET /actuator/health/readiness` is healthy and includes the required MongoDB/Redis dependencies.
4. The application serves the login page over HTTPS and does not expose secret values in responses or logs.
5. If public demos are enabled, `GET /api/v1/demo/accounts` returns exactly six roles and no password is requested.
6. Click each demo role once: `academy-admin`, `instructor`, `learner`, `talent-admin`, `recruiter`, and `candidate`.
7. Confirm Academy owner/instructor/learner and Talent owner/recruiter/candidate land in the correct workspace with role-scoped navigation.
8. Confirm Academy learner and Talent candidate can see Tutor only after a released result; active attempts must not expose Tutor.
9. Confirm demo accounts cannot change password, reset password, change profile/avatar, or link Google; do not apply this restriction to real accounts.
10. Verify one controlled real-account login, password change, Google flow, support ticket, and platform-admin flow using disposable/test identities.

`DEMO_SHARED_DATABASE` is intentionally not part of this required deployment list:
it must not be made mandatory and must not reintroduce a database-name restriction.
