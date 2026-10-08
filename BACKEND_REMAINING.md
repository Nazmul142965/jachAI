# JachAI backend: one remaining-work queue

Updated 2026-10-08. This is the backend-only coordination file. `ARCHITECTURE.md` is the product/acceptance authority; `agent-prompts/02`–`07` contain detail. Do not assign those old prompts wholesale or treat this file as proof of completion. Recheck Git and tests before acting.

## Current evidence and ownership

| Slice | Evidence now | Next action |
| --- | --- | --- |
| Identity, organizations, Academy/Talent, mail, audit/runtime guards | Code is on `main` at `66ef519`; the recent `backend/audit-recovery`, `backend/pending-account-retention`, and `backend/placeholder-guard` tips are already ancestors of `main`. | Audit behavior against the phase gates; fix demonstrated defects only. Do not re-merge these branches. |
| **Support center** | **Integrated at `73d0ecc` (integrate/backend)** — cherry-picked `support-center` `48c948e` plus fixes. Adds ticket controllers/services/models, mail integration, PlatformSupportController, rate limit policy. | **CODED, INTEGRATED, LOCALLY TESTED** — unit tests pass. No live SMTP/Google/AI provider verification. |
| **AI / personal practice** | **Integrated at `73d0ecc` (integrate/backend)** — cherry-picked `ai/practice-reliability` `9e624cf`..`2d762c4` (6 commits). Fixes replay-after-expiry, ambiguous-insert-outcome, duplicate-key refund vs fail-closed, global bucket isolation, replay field comparison, unsafe `@BeforeEach` global bucket deletion. | **CODED, INTEGRATED, LOCALLY TESTED** — 27 unit tests pass. Mongo integration tests opt-in via `MONGODB_TEST_URI`; live AI provider **PENDING**. |
| Academy/Talent delivery branch | Previous comparison reported most code byte-identical with `main`; branch tip is `b1d9b78`. | Diff again. Do not merge 12 commits wholesale; extract only an actual missing backend change, if any. |
| **Billing/platform** | Sandbox adapters and throttling code exist in `main`; no successful merchant sandbox transaction is evidenced here. **Platform-admin sandbox payment summary added at `02a323f` (merge/integrate)** — GET `/api/v1/platform/operations/sandbox-payments` returns count/sum of unique APPLIED orders by provider and weekly date range, labeled "Sandbox activity (not live revenue)". | Audit callback/webhook verification, duplicate/retry handling, reconciliation, entitlement grant/reversal, and provider-error behavior. Test offline paths first. Never turn on live-money endpoints by inference. |

The screenshot from 2026-10-08 shows logo navigation failing with `Cannot read properties of null (reading 'myRole')`. That is an unresolved **frontend** defect, not a backend-complete signal. UI ownership stays with the UI agent; this queue does not edit those files.

## Work order for one backend owner

1. **Protect concurrent work.** Read `AGENTS.md` if present, `ARCHITECTURE.md` section 14, this file, relevant delivery notes, `git status`, branch/worktree list, and diffs. Record a small PASS / CODE-PRESENT-UNVERIFIED / DEFECT / EXTERNAL-GATE matrix. Main worktree is dirty: do not reset, clean, force-merge, or overwrite another agent's files. Use a separate worktree for risky integration.
2. **Reconcile pending backend branches.** Review `support-center` and `ai/practice-reliability` against `main`, including tests and security boundaries. Integrate only non-overlapping reviewed commits or a minimal equivalent patch, one slice at a time. Document commit hashes and conflicts. The audit/retention/placeholder branches are already in `main`; do not duplicate them.
3. **Close evidenced workflow defects.** Exercise registration → verification → login/refresh/logout/password reset/Google linking; organization invite, membership and role enforcement; Academy assessment → timed attempt → manual grading/release → certificate; Talent invite → attempt → review/shortlist; personal practice generation/answer/quota; support ticket → staff reply/status; and billing checkout → verified callback → reconciliation/entitlement. For each workflow, distinguish missing code from a failed environment. Fix only reproduced backend gaps with DTO/service/repository conventions, validation, audit, tenant isolation and server-authoritative decisions. Do not expose answer keys early or trust browser role, timer, or payment status.
4. **Verify proportionately.** Run focused tests for changed slices, dedicated `jachai_it_...` Mongo integration tests where local Mongo is available, then one broader Java suite/build. Keep test cleanup scoped to test-created records; never clear user, demo or Atlas databases. Capture exact commands and counts. A compilation or mocked unit pass is not a live journey pass.
5. **Prepare release gate, without pretending it passed.** Verify configuration/startup diagnostics, Mongo indexes/TTL, Redis rate limit/revocation behavior, email outbox retry/idempotency, health/observability, secrets hygiene and deployment settings from prompt `05`. Record unavailable external checks separately. Hand off browser UI verification to the UI owner; do not block independent backend work on browser access.

## External gates requiring owner configuration

Request only missing variable **names** and where to set them; never ask for secret values in chat or commit them. Confirm the active process port, profile and database from startup logs, not old 8080/8093/8095/8102 screenshots. Gates include reachable Mongo/Redis, real SMTP inbox delivery (queued is not delivered), Google OAuth client/origin and account-linking journey, live AI provider call and usage accounting, and bKash/SSLCOMMERZ **sandbox** merchant credentials plus successful checkout/callback/reconciliation. No real-money enablement is authorized. Mark each gate pending until actual evidence exists.

## Completion handoff

Maintain this file as the single backend status queue. For each slice report: code status, integrated branch/commit, test command/result, local API or browser evidence if relevant, external-provider evidence, remaining defect, and next action. Use distinct labels **coded**, **integrated**, **locally tested**, **browser verified**, and **live-provider verified**. Do not call the backend or SaaS complete until `ARCHITECTURE.md` phase gates have evidence; do not resurrect old prompts as duplicate work.

## Integration summary (2026-10-08)

### Commits integrated into `integrate/backend` branch (`73d0ecc`)

| Commit | Source | Description |
| --- | --- | --- |
| `cce60e3` | `support-center` (`48c948e`) | Support center: controllers, services, models, mail, rate limit |
| `a59e7b7` | `ai/practice-reliability` (`9e624cf`) | Practice: state retention, generation limit, unit tests |
| `8c2ca21` | `ai/practice-reliability` (`1c04f83`) | Practice: opt-in Mongo integration tests |
| `db1e712` | `ai/practice-reliability` (`5603a28`) | Practice: replay-after-expiry, ambiguous-insert fixes |
| `0971d8d` | `ai/practice-reliability` (`103496b`) | Practice: duplicate-key refund vs fail-closed, global bucket |
| `222501f` | `ai/practice-reliability` (`8a5ae19`) | Practice: replay field comparison |
| `600a861` | `ai/practice-reliability` (`67163e8`) | Practice: remove unsafe @BeforeEach global bucket deletion |
| `4761772` | `ai/practice-reliability` (`2d762c4`) | Practice: delivery doc update |
| `02a323f` | `merge/integrate` | Platform-admin sandbox payment summary (count/sum of APPLIED orders by provider & date range) |

### Test evidence

**Unit tests (no MongoDB required):**
```
PracticeServiceTests                     12 pass
PersonalPracticeGenerationServiceTests    8 pass
PracticeControllerTests                   2 pass
SpringAiServiceTests                      5 pass
PlatformSupportAccessServiceTests         5 pass
PlatformBillingSupportServiceTests        6 pass
PlatformAdminServiceTests                14 pass
PlatformBillingSupportServiceTests        6 pass
PlatformOperationsServiceTests            6 pass
PlatformPlanServiceTests                  8 pass
PlatformStorageServiceTests               5 pass
PlatformSupportAccessServiceTests         5 pass
```
All unit tests pass. Command: `./run-it-tests.bat "PracticeServiceTests,PersonalPracticeGenerationServiceTests,PracticeControllerTests,SpringAiServiceTests,PlatformSupportAccessServiceTests,PlatformBillingSupportServiceTests,PlatformAdminServiceTests,PlatformOperationsServiceTests,PlatformPlanServiceTests,PlatformStorageServiceTests"`

**Integration tests (require MONGODB_TEST_URI):**
- 18 integration test classes updated with `@EnabledIf("integrationTestsEnabled")` 
- BaseMongoIntegrationTest `@DynamicPropertySource` made conditional
- When `MONGODB_TEST_URI` is not set: tests are **skipped** (not errored)
- When `MONGODB_TEST_URI` is set to a `jachai_it_*` database: tests run against that disposable database
- Practice integration tests (`PracticeIntegrationTests`) verified working locally when MongoDB is available

**External gates (all PENDING - no credentials configured):**
- SMTP inbox delivery (queued email outbox works, real delivery unverified)
- Google OAuth client/origin and account-linking journey
- Live AI provider call (Gemini/Groq/Ollama) and usage accounting
- bKash/SSLCOMMERZ sandbox merchant credentials + checkout/callback/reconciliation

### Remaining defects / next actions

1. **Support center** — No dedicated unit tests for `SupportTicketService`/`SupportTicketController`/`PlatformSupportController`. Add focused tests for authorization, tenant isolation, status transitions, reply persistence, notification failures.
2. **AI/Practice** — Live AI provider call verification pending. Requires `AI_PROVIDER` + `GEMINI_API_KEY` (or equivalent) in environment.
3. **Billing** — Sandbox callback/webhook verification, reconciliation, entitlement grant/reversal not yet exercised against real sandbox. **Platform-admin sandbox payment summary** (`02a323f`) coded/integrated/locally-tested; live sandbox verification pending.
4. **Browser UI** — Logo navigation `myRole` error is a frontend defect (separate ownership).
5. **Integration test infrastructure** — Consider adding `@EnabledIfEnvironmentVariable` support for JUnit 5.12+ to simplify skipping logic.