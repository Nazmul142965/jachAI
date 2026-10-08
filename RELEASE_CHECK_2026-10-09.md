# JachAI pre-deployment release check

**Revision:** `ecab0e9` on `main`  
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
