# JachAI working handoff

## Latest UI defect fix (2026-10-09)

Commit `e13921b` is on `main`. The account dropdown was staying open across
logout/login transitions because its panel state was not reset when the
authenticated shell was hidden or shown. `app.js` now closes
`#account-menu-panel` and resets `aria-expanded` to `false` in both
`showSignedOut()` and `signedIn()`. This keeps Profile, Support, and Sign out
hidden until the user explicitly opens the Account menu. JavaScript syntax and
the frontend journey suite passed 151/151 after the fix.

## Latest delivered change log (2026-10-08)

`main` and `support-center` are aligned at `403da4d` (the documentation correction is
tracked in the next release-check commit). The following table records the
recent user-requested work and the reason for each change so a later maintainer can
understand the decisions without reconstructing the commit history:

| Commit | Change | Why it was done |
| --- | --- | --- |
| `6d9be63` | Replaced the unsupported derived MongoDB `BigDecimal` sum query with a custom aggregation repository fragment. | Spring Data tried to reflect into `java.math.BigDecimal`, so the application failed during startup while creating `PaymentOrderRepository`. |
| `f78e16e`, `9dd9896` | Hardened SSLCOMMERZ and bKash sandbox checkout/callback validation and reconciliation. | A browser redirect alone must not verify a payment; provider identity, callbacks, duplicate notifications, tenant boundaries, and entitlement application need server-side checks. |
| `00b649d` | Added server-side exam tab-leave counting and a warning after repeated leaves. | The original browser telemetry was not enough to surface repeated tab switching to reviewers. |
| `22370b1` | Preserved the signed-in JachAI tab during sandbox checkout and added safe cancellation for unfinished payments. | Opening checkout in the same tab could appear to log the customer out; customers also need a way to cancel before verification/application. |
| `e785e37` | Added authenticated avatar upload/removal with resize, JPEG conversion, output-size bounds, and a separate `user_avatars` collection. | Users need visible profile images without putting large binary data into the main user document or exhausting the primary database unnecessarily. |
| `43ca4f7` | Added safe direct Google sign-in and automatic account linking for authoritative verified email identities. | A first-time Google user should not have to register again, while conflicting, disabled, or unverified identities must remain protected. |
| `123b338`, `3261c71`, `401cf13` | Kept demo login visible, enabled the six role shortcuts in supported profiles, and removed the database-name restriction. | Demo access should remain discoverable and work on the configured database without silently disappearing or forcing a special database name. Demo users remain protected from password, profile, avatar, and Google-identity changes. |
| `092f2e1`, `1e13aee` | Connected user Support tickets and the platform-admin Support inbox to the existing APIs. | Users need a clear place to ask for help, and operators need a controlled place to reply, add internal notes, and change ticket status. |
| `b199e91` | Consolidated Profile, Support, and Sign out into one account menu; removed duplicate sidebar/footer actions and exposed Messages. | The previous layout repeated the same actions and made Academy messaging difficult to discover. |
| `bb9bf98` | Clarified the AI Tutor wording as Academy-focused while preserving the existing Academy learner guard. | The initial product decision described Tutor as Academy-only, so the UI needed to communicate that scope accurately. |
| `403da4d` | Enabled AI Tutor for Talent candidates as well as Academy learners, from a released result and the account workspace. | Candidates should be able to learn from an exam after it ends, and practice independently later; active in-progress exams remain blocked by the server guard. |

The latest Tutor policy is therefore: **Academy learners and Talent candidates may use Tutor
after a submitted result is graded and released; neither role can use it during an active
exam.** Staff roles do not receive the learner/candidate Tutor entry point. The frontend
journey suite remains at 151/151, Java compilation passes, and the latest Tutor change is
already fast-forwarded into `main`.

## Delivery verification (2026-10-08, current revision)

The prior local verification revision was `b50db14`; the current `main` revision is
the commit shown by `git log -1`. The application was rebuilt and restarted on the
required local URL `http://127.0.0.1:8080/` with the `demo` profile and disposable
Mongo database `jachai_demo_local`; the process was healthy at `/api/v1/health` and
`/actuator/health/liveness`. No customer database or real-money endpoint was used by
that run.

Real Chrome clicks passed for all six demo buttons. Each issued the corresponding `POST /api/v1/demo/login/{key}`, created an in-memory browser session, reached the expected dashboard route, and showed the expected isolated workspace/role:

| Key | Browser result |
| --- | --- |
| `academy-admin` | `#/overview`, `Academy · Owner / Org Admin`, `Demo Academy` |
| `instructor` | `#/overview`, `Academy · Instructor`, `Demo Academy` |
| `learner` | `#/start`, `Academy · Learner`, `Demo Academy` |
| `talent-admin` | `#/overview`, `Talent · Owner / Org Admin`, `Demo Talent` |
| `recruiter` | `#/overview`, `Talent · Recruiter`, `Demo Talent` |
| `candidate` | `#/start`, `Talent · Candidate`, `Demo Talent` |

Live sign-in screenshots are `target/frontend-live-evidence/sign-in-demo-desktop.png`, `target/frontend-live-evidence/sign-in-demo-mobile.png`, `target/frontend-live-evidence/demo-live-academy-owner.png`, and `target/frontend-live-evidence/demo-live-talent-candidate.png`. Desktop/mobile checks measured the password field at 371px/301px, found both recovery links, and found six dynamic demo buttons with no invalid server-rendered preview links. The mocked real-browser suite passed 151/151 checks across six journeys after fixing the 320px/375px sidebar navigation grid. Java 21 compilation passed; `JachAiApplicationTests` passed 31/31; focused billing/entitlement/reconciliation tests passed 99 executed with 14 opt-in Mongo tests skipped because no `MONGODB_TEST_URI` was supplied.

### Branch and worktree disposition

All worktrees were inspected against current `main` at file and behavior level. Dirty/untracked files in the main worktree and active worktrees were preserved.

| Branch | Worktree | Disposition |
| --- | --- | --- |
| `main`, `support-center` | `E:\Advance Java\jachAI` | Current delivery branch; fast-forward main after commit |
| `appmod/java-upgrade-20261003170058` | `C:\Users\nazmul\Documents\Codex\2026-09-29\pu\jachai-platform-admin` | Same tree as main; no unique work |
| `audit/billing-sandbox-flow` | `E:\Advance Java\jachAI-payment-audit` | Its 12 production payment properties are already on main via `3ed3943`; keep historical audit branch |
| `ai/practice-reliability` | `E:\Advance Java\jachAI-practice-ai` | Practice limits, retention, replay safety, and opt-in tests already integrated on main; no duplicate merge |
| `backend/atlas-config`, `backend/audit-recovery`, `backend/connection-doctor`, `backend/dependency-hygiene`, `backend/guard-reports-source`, `backend/local-secrets-file`, `backend/pending-account-retention`, `backend/placeholder-guard`, `backend/startup-diagnostics`, `preflight/fix-mongo-preflight` | Separate `E:\Advance Java\jachAI-*` worktrees | No file delta from main; already integrated |
| `feat/platform-admin-dashboard`, `integrate/backend`, `frontend/product-home`, `frontend/ui-refresh` | Separate worktrees | No file delta from main; already integrated |
| `frontend/complete-journeys` | `E:\Advance Java\jachAI-journeys` | Google UI commit integrated as `c59f861`; verification-only commits add no missing product code |
| `frontend/dashboard` | `E:\Advance Java\jachAI-frontend` | Message loading delta integrated as `d426172`; remaining branch commits are already present or documentation-only |
| `test/support-center` | `C:\Users\nazmul\Documents\Codex\2026-09-29\pu\jachai-support-tests` | Support tests already integrated through `64c23c5`; no duplicate merge |
| `frontend/react-dashboard` | `E:\Advance Java\jachAI-react-ui` | Deliberately excluded: second UI conflicts with the static same-origin architecture |
| `delivery/academy-talent-ui` | `E:\Advance Java\jachAI-claude-delivery` | Deliberately excluded: broad incompatible snapshot with unrelated deletions and stale configuration |
| `generated-braid`, `halved-shade`, `lavender-tartan` | `.kilo\worktrees\...` | Same commit/tree; no unique work |
| `merge/integrate` | `E:\Advance Java\jachAI-merge` | No unique file delta from main |

The remaining untracked `.kilo/`, `.playwright-mcp/`, and `agent-prompts/` files were not touched or added.

## Verification checkpoint (2026-10-08)

The baseline `support-center`/`main` revision for this checkpoint was `a257623`; the current verified revision is documented above. Java 21 compilation passed, `JachAiApplicationTests` passed, and the focused billing flow/reconciliation suite passed with 0 failures. The application was subsequently restarted on port 8080 in isolated demo mode and its current evidence is documented above.

The six-role path is designed for the same hosted production deployment and database:
enable `DEMO_PUBLIC_ACCESS=true` with an HTTPS `APP_PUBLIC_BASE_URL`. The current
code does not require `DEMO_SHARED_DATABASE` or a `jachai_demo_*` database name;
that setting is retained only for configuration compatibility. `DEMO_PREVIEW_URL`,
a separate deployment, and a separate demo database are not required for this flow.
Hosted browser verification is still pending because no public production URL is
configured in this environment. Existing candidate worktrees were audited; their
missing commits either are already represented on `main` or contain broad
incompatible deletions and were not merged.

Parallel assignment boundaries and paste-ready prompts are in
`AGENT_WORKSTREAMS.md`. Assign one writer per lane and integrate reviewed
commits only after branch handoff; this file is not a completion claim.

## Current continuation (2026-10-06)

Payment checkpoint: both sandbox adapters now accept the local launcher's
`http://127.0.0.1:<port>` as a browser callback origin, while rejecting
non-loopback HTTP and malformed origin URLs. Availability flags also require
a valid callback origin. Focused origin and gateway tests passed 10/10. No
merchant credentials are present in this shell, so no real sandbox checkout,
IPN, or plan activation was exercised; do not claim payment is live yet.

At `5314dbc`, guarded demo sign-in now seeds one usable Academy and one Talent
assessment journey through the existing question, publish, assignment, and
campaign services (`e46412b`), without direct writes to those business
collections. Focused fresh/repeat fixture tests passed; live Atlas seeding and
browser sign-in are still unverified. `d53dc7a` guards the account home against
stale responses after workspace changes. A Maven test run while IntelliJ was
serving the same `target/classes` caused a transient `EmailLinkGuard$1`
`NoClassDefFoundError` in the running mail worker; the class exists in the
current build, and a complete app restart is required. Do not run Maven
against this checkout's `target/` while an app process is running; use an
isolated worktree/build output for concurrent verification.
The next demo hardening slice ensures a failed fixture does not block sign-in
and a closed sample campaign is not reused. Focused demo tests passed; this
behavior still needs a live disposable database walkthrough.

Main includes role-specific account-home work rows (`d8207ce`). The local-demo
slice adds six passwordless role shortcuts, guarded by the `demo` profile and
loopback client; it is not guarded by a database name. Demo/auth unit tests,
script/JS syntax and a mocked two-check browser shortcut passed, but live Atlas
seeding and browser sign-in against the real backend had not been run at that
checkpoint.
Do not merge stale frontend worktrees or put demo credentials in the public
frontend. `README.md` documents `scripts/start-local.ps1 -Demo`.

AI practice diagnostics now distinguish known provider HTTP categories from
personal 20/day and platform-wide caps; see `PRODUCT_UI_HANDOFF.md`. Provider
quota balance is not available to this app, and no live Gemini/Groq call has
been verified. Do not claim an `AI_PROVIDER_RATE_LIMIT` response proves which
provider quota window was exhausted.

Updated: 2026-10-04. This file is a living checkpoint, not a claim that the project is finished. Inspect the worktree and `ARCHITECTURE.md` before relying on any status below.

Ownership checkpoint (2026-10-04): `7a09f25` prevents a second owner transfer from overwriting recovery metadata when the preceding audit insert has not succeeded. `OrganizationServiceTests` passed 7/7. A dedicated opt-in test then passed 1/1 against local MongoDB `jachai_it_ownership_20261004`: two sequential transfers, simulated loss and repair of the first audit event, history visibility to the new owner, and old-owner transfer denial. The test is committed with this checkpoint. This does not verify Atlas, real concurrent transfers, or the browser flow.

AI quota checkpoint (2026-10-04): Redis AI quota keys no longer contain raw organization/user IDs; the v3 keys HMAC both scopes while preserving one Redis Cluster slot for the atomic two-counter Lua script. `AiQuotaServiceTests` passed 4/4. The key namespace changed before deployment, so no live counters require migration. Live Redis verification remains open: no Redis listener or binary is available locally, and the installed WSL distribution cannot start because its virtual disk path is missing. No provider model or AI prompt was changed.

Demo checkpoint (2026-10-04): an isolated loopback app is running at `http://127.0.0.1:8093/` against local database `jachai_demo_public_20261004`. This is throwaway demo data, not Atlas or production. It contains a verified owner account, separate Academy and Talent organizations, approved questions, published assessment version 2 in each, a learner and candidate with two-attempt assignments, a released passing Academy result plus a valid public certificate, and a submitted Talent candidate with an audited SHORTLISTED review. Version 1 demo assessments had an ambiguous question; version 2 corrected it, and the old Talent campaign was closed before creating the current version-2 campaign. The browser can display this data, but no visual browser walkthrough is claimed. No demo password, verification token, or access token is stored in this repository. The existing 8080 and 8092 apps were not restarted. `main` is clean at `8f9cb67` before this handoff update; the old `frontend/dashboard` worktree remains separate and uncommitted.

Latest checkpoint (2026-10-04): `main` is clean at `a919e84` before this handoff update. Queued result/certificate mail now rechecks recipient eligibility at delivery (`489b6b1`; focused unit test passed). AI daily organization/user quotas now use one Redis Lua reservation so a rejected request consumes neither counter (`a919e84`; focused unit test passed); the script is not yet live-Redis verified. The opt-in `CampaignEmailInvitationIntegrationTests` passed 2/2 against disposable local MongoDB `jachai_it_invitation_20261004`, covering retained accepted records and stale-revision rejection; this is not Atlas or SMTP verification. The three in-app Academy batch, Talent campaign, and learner assessment-detail screens are already in `main` and served by the loopback demo on port 8092. The separate `frontend/dashboard` worktree is at `133fe26` with uncommitted public-page redesign and routing/security edits; do not overwrite that worktree or claim those uncommitted pages are integrated. Its `FRONTEND_PROGRESS.md` has stale checklist text; use source and Git state instead. The existing port-8080 process has not been restarted. No new full-suite, browser, Redis, Atlas, SMTP, AI-provider, gateway, or Docker verification is claimed.

Newest checkpoint (2026-10-04 morning): `main` is clean at `0cd6233`. Recent committed backend slices add audited, ticketed platform assessment-metadata access (`5feb45d`), Academy feedback email notification (`392cb55`) and publish audit (`02d4518`), a read-only-by-default all-tenant usage-review operator script (`dcd6d28`), and removal of false manual incidents for empty invitation windows (`8b21eae`). The platform and tenant dashboards expose the support-access audit via `app.js` (`3dd05de`); JavaScript syntax passed, but browser interaction was not verified because the in-app browser connection failed during setup. The current main app is running at `http://127.0.0.1:8092/` against the separate local `jachai_demo_20261004` database; `/` and health returned 200, the served JS contains the new support panel, and the listener was verified as `127.0.0.1:8092`. The updated local launcher forces loopback (`0cd6233`). An older Java app on port 8080 was observed listening on `0.0.0.0`; it was deliberately left untouched pending user approval to restart because it might hold an active session. The separate `frontend/dashboard` worktree is still at `133fe26` with uncommitted page/security/controller/template changes; do not merge its dirty files or edit that worktree. The new frontend pages have not been integrated into main. Focused tests for the backend slices passed, but no new full-suite, Atlas, Redis, SMTP, AI, gateway or Docker verification is claimed.

Talent live-local checkpoint (2026-10-04): the current JAR ran on isolated port 8091 against the disposable `jachai_it_academy_smoke` local MongoDB database. Real HTTP calls created a Talent workspace, approved/published an assessment, opened a campaign, emailed an existing verified account outside that tenant, accepted the invite to create its candidate membership and assignment, started/saved/submitted an attempt, compared the candidate, saved and read a shortlist audit event, released the result, and fetched it as the candidate. Before release the candidate saw `RELEASE_PENDING` with no score; candidate comparison access and invitation acceptance by a different verified account both returned 403. Reaccepting the same token returned the same assignment. The isolated app was stopped afterward; the existing port-8080 app was not touched. This does not verify Atlas, SMTP delivery, live Redis, browser UI, or payment/AI providers.

Academy live-local checkpoint (2026-10-04): a current Java 21 JAR ran on isolated port 8091 against disposable local MongoDB database `jachai_it_academy_smoke`. Real HTTP calls completed owner and learner registration, email verification, login, Academy organization creation, question approval, assessment publication, learner membership and assignment, attempt start, answer save, submit, 100% released result, certificate issuance, and public certificate verification. The candidate attempt response had no answer key; public verification had no learner email. A dedicated `RegistrationVerificationIntegrationTests` case also passed against local MongoDB. This is local MongoDB and development email transport, not Atlas, SMTP, browser UI, Redis, AI, payment sandboxes, or production readiness. Earlier statements below that the Academy HTTP journey is unverified are superseded by this paragraph.

Manual TEAM live-Mongo checkpoint (2026-10-04): a new dedicated local Mongo integration test exposed and then verified a fix for repeat grants with nanosecond expiry values. MongoDB stores dates to milliseconds; `PlatformPlanService` now normalizes the requested expiry before idempotence comparison and persistence. `PlatformPlanServiceTests` passed 8/8 and `PlatformManualPlanIntegrationTests` passed 1/1 on local `jachai_it_platform` with JDK 21. This verifies the sequential grant/revoke/entitlement path and unique revision index locally, not Atlas or real concurrent grant behavior. The frontend worktree has since committed its manual TEAM controls as `07b1e6b`; it remains separate and is not yet merged into `main`.

Checkpoint (2026-10-04): The original worktree is back on `main` at `63f9ca1` before this documentation update. The separate `frontend/dashboard` worktree is at `6f2e790` with uncommitted Platform manual TEAM UI edits in `app.js` and `index.html`; preserve those edits and let its frontend owner finish and commit them. The three new in-app workspace screens are already committed, so `FRONTEND_PROGRESS.md` is stale where it lists them as remaining. `node --check` passed on the frontend worktree's current JavaScript, but its uncommitted slice has not been browser-verified here. A separate `appmod/java-upgrade-20261003170058` branch exists at the same commit as `main`; `pom.xml` still targets Java 21, and no Java 25 source or build change was present in the original worktree. Do not treat the upgrade as complete or merge it into the product without a deliberate decision. The root README had an outdated claim that usage reconciliation did not exist; preview/repair APIs are implemented, while live production verification and manual review of ambiguous evidence remain outstanding.

Latest checkpoint (2026-10-03 late): `main` now includes the committed public Academy/Talent pages and three in-app workspaces through merge `104cb7d`; the separate frontend worktree still has uncommitted manual-plan UI work and must not be edited from `main`. Backend `54270dd` adds a learner-owned assignment detail route; `b3afbdd` connects the learner screen to assessment metadata and granular result states. The fixture browser walkthrough passed **76/76 checks across six journeys**, and the Java 21 local app was restarted successfully at `http://127.0.0.1:8080/`; `/`, `/academy.html`, `/talent.html`, the public certificate template and `/app.js` returned HTTP 200. The Windows PowerShell startup helper was fixed in `f8860ce` after its first real restart attempt exposed compatibility bugs. These are local/fixture results, not proof of live authenticated workflows, Atlas, Redis, SMTP, AI, Google, payment gateways or Docker. The next paragraph is an earlier checkpoint superseded where facts differ.

Current integration checkpoint (2026-10-03 evening): frontend work through `4639afa` was merged into `main` as `565f43e`; newer screens are being written in the separate `frontend/dashboard` worktree and must not be edited here. The browser fixture suite passed **53/53** on the earlier merged tree, but uses mocked API responses. Backend commits `ae4eada` and `fe7787d` add learner-safe assignment metadata and explicit grading/release result states; the focused Java 21 attempt suite passed **31 tests**. The previous full offline suite passed **417 tests** before these changes, so a new full-suite result is not yet claimed. A real local MongoDB 8.0 standalone run of all five opt-in billing integration classes passed **27/27** after correcting certificate fixtures and the `mail_due` index assertion; see `MONGODB_INTEGRATION_TESTS.md`. The local app and MongoDB have run successfully, but the end-to-end Academy account/assessment journey remains unverified. Live Atlas, Redis, SMTP, AI, Google, both payment sandboxes, and Docker remain unverified. Do not use a credential found in `.idea` or an earlier chat message; the previously shared Atlas password should be rotated before creating a dedicated Atlas test URI. Continue from the current source, not the older milestones below.

## Objective and user decisions

Follow-up live check (2026-10-03): `AssessmentMetadataIntegrationTests` passed **1/1** on the dedicated local `jachai_it_*` MongoDB database. It verifies that the new batched published-version lookup used by learner assignment summaries cannot return another tenant's metadata. This is separate from the 27 billing integration tests above; it is not a live browser or Atlas verification.

- Complete the single Spring Boot application and responsive frontend described in `ARCHITECTURE.md`; no microservices.
- The product has Academy and Talent modules sharing one assessment engine.
- Preserve concurrent user changes. Work autonomously; ask only for genuinely required choices or credentials.
- Use Lombok `@RequiredArgsConstructor` when it removes ordinary dependency constructors, but keep Java records for immutable DTOs/documents and do not add unnecessary constructors.
- Add tests alongside each vertical workflow. Commit locally after a phase's actual acceptance gates pass, with phase-specific changes and a clear message.
- Keep cloud AI on free tiers. Gemini is for question drafting, Groq for tutoring; Ollama is the local option. User will obtain keys near project completion. Never request or paste secrets into chat/code/commits.
- User replaced Stripe with two independent Bangladesh payment paths: direct bKash sandbox and SSLCOMMERZ sandbox, with visible demo transactions but no live-money endpoints or credentials. Payment must be validated server-to-server before TEAM activation. User also requested optional Google sign-in; basic Google Identity Services needs a web client ID, not paid Identity Platform.
- MongoDB Atlas Free has a hard storage limit; keep persistent AI logs/history bounded or absent, use TTL for temporary data, and monitor storage.

## Repository and verification status

- Project: `E:\Advance Java\jachAI`.
- Local Git branch `main`; baseline commit `524db0b` records the state that existed when Git was first initialized. It is **not** a completed-phase commit. Subsequent identity changes are a Phase 2-scoped slice, not a release sign-off. No GitHub remote was configured at this checkpoint.
- At the baseline, Maven reported 22 passing tests. Later verified milestones: 30 after password reset/JWT, 34 after Phase 3 result release, 42 after Academy certificates, 46 after frontend/attempt resume, 58 after Talent campaigns, 67 after access-token revocation/security-version tests, 74 after Academy feedback, 84 after Academy course/batch/enrollment, 92 after batch assessment distribution/progress, 101 after Academy direct messaging, 108 after Talent integrity telemetry, 116 after Talent email invitations, 126 after encrypted email outbox, 132 after request rate limits, 141 after plan entitlement gates, 153 after Google sign-in/account-linking tests, 161 after SSLCOMMERZ sandbox billing unit/smoke tests, 167 after direct bKash adapter and renewal tests, 170 after admin payment reconciliation tests, 174 after Academy cohort analytics, 179 after certificate audit, 185 after certificate email/public page, 188 after staff pending-review queue, 195 after organization result-release defaults, 199 after Talent review audit, 200 after email invitation creation-date preservation, 206 after result-release email, 207 after candidate selector pagination, 220 after organization membership governance, and 229 after owner transfer. This does not verify live MongoDB, Redis, SMTP, Google Sign-In, Gemini, Groq, Ollama, either payment sandbox, or a browser walkthrough.
- Maven inside the managed Windows filesystem sandbox sometimes emits `Fatal Error: Cannot close compiler resources` without Java diagnostics. The same offline command completed successfully outside that sandbox. Treat a failed run as unverified until a later successful full test run, not as proof of success.
- Local test profile excludes MongoDB and uses mocks in the application smoke test. A live infrastructure integration test is still needed.

## Work in progress right now

The current slice adds a separate Platform section to the dashboard. A verified platform admin can inspect operations counts, paged organizations and orders, tenant billing, quota reconciliation preview/repair, and the repair audit; a platform admin with no own workspace can still open it. It uses `/platform/me` only to show navigation, with all authorization enforced server-side. The platform API operations summary uses indexed count queries and has an opt-in real-Mongo index assertion, but no live query/index execution yet. Java 21 full offline Maven tests passed **395/395, 0 failures/errors/skips** on the combined backend/frontend revision; Node JavaScript syntax also passed. This revision packaged into an executable JAR with tests skipped in the separate packaging command. The real-Mongo integration classes still executed **0 tests** because `MONGODB_TEST_URI` is not configured. Live MongoDB/Redis/SMTP, payment sandboxes, AI providers, Google sign-in, Docker, and a browser walkthrough remain unverified.

Earlier local commits include `ce672b3` short-answer manual grading with audit/retry controls, `0abfa90` tenant/date indexes for billing counts, `d270c25` billing reconciliation TTL/ABA safety, and `e78c3b5` Talent campaign email-invitation status listing. At `e78c3b5`, the full offline suite passed **328 tests, 0 failures**; that historical result does not cover later work. JavaScript syntax and `git diff --check` have also passed on later slices. None of this verifies live MongoDB, Redis, SMTP, AI, payment, proxy, Docker, or browser behavior.

Earlier committed slices include Phase 2 identity/security `17c49c8`, result release `f6e6431`, Academy certificates `0abdf4f`, Talent campaigns `fe76cb0`, Academy feedback `cf7e741`, catalog/enrollment `5c40383`, batch distribution/progress `06570a0`, Academy messaging `f32e1cf`, Talent telemetry `7bf0041`, email invitations `16d5a24`, encrypted email outbox `e84e733`, rate limits `0f7d43f`, plan entitlements `4f491b3`, Google sign-in `e6fbc90`, SSLCOMMERZ sandbox billing `a9db1fd`, direct bKash sandbox/renewal `5eea778`, payment reconciliation `22125e3`, Academy cohort analytics `5bd7045`, certificate audit `6962165`, certificate email/public page `ec755e4`, staff pending-review queue `09911e4`, organization result-release defaults `f75c440`, Talent review audit `6576c4b`, frontend pagination `1a7a279`, email invitation quota-date fix `289d880`, deployment/readiness `bf3ca89`, result-release notification email `66eaad4`, candidate selector pagination `97e44bd`, membership governance `118464d`, owner transfer `1e28ccd`, read-only platform boundary `0650f32`, dashboard pagination `0fcae7c`, billing usage reconciliation `3f56ed7`, and controlled platform-role grants `2fbfe38`. An executable JAR was previously packaged offline using `C:\Users\nazmul\.m2\repository`. Docker is absent, so image build/run and live production readiness are unverified. The project's `.build-cache\m2` lacks the Maven JAR plugin; an online attempt from it failed DNS resolution for Maven Central, so use the populated user cache until network access works. Live MongoDB/Redis/email/Google/browser/payment delivery and complete phase gates remain unverified. There is no `MONGODB_URI` in this shell, no local MongoDB listener, and no Docker; the user configured the Atlas URI only in IntelliJ. Billing reconciliation no longer automatically decrements invitation counters from TTL-pruned records, and a monotonic revision protects its CAS from same-millisecond ABA; these fixes passed offline tests but still require live MongoDB verification. Invitations with expired evidence need operator review rather than automatic downward repair.

## Remaining major work

Use `ARCHITECTURE.md` section 14 as the authoritative phase gates. Key gaps include exceptional audited platform support access, live MongoDB index/concurrency/audit verification of manual plan events and other operations, live verification of ownership transfer, membership updates/audit, token revocation, messaging, telemetry, invitation, email jobs, review queries/indexes, settings versioning, rate-limit counters, entitlement reservations, cohort aggregation, and audit retries against MongoDB/Redis/SMTP; real-browser/live-API walkthroughs; live bKash and SSLCOMMERZ sandbox verification (especially lost-callback query and independent validation); Google Sign-In and Gemini/Groq/Ollama live verification; and running container/deployment checks. Billing reconciliation defers invitation downward repair when TTL may have removed evidence; this needs an operator procedure and live MongoDB concurrency/TTL tests. General assignment still requires an existing active member; Talent email invites can onboard an external candidate. Do not call the product complete because the offline suite passes.

## Support and account UX checkpoint

The authenticated account menu now opens a first-class Support screen. Users can select an optional workspace, create a categorized support ticket, and see their ticket references/statuses from `/api/v1/support/tickets`; the existing backend validates ownership, membership, rate limits, and SMTP notification behavior. The Support screen is available from the sidebar and the Profile menu. Support and messaging service tests passed, and the frontend journey suite remains green.

## Next-agent prompt

> Continue implementing JachAI in `E:\Advance Java\jachAI` to completion against `ARCHITECTURE.md`. First read `HANDOFF.md`, `ARCHITECTURE.md`, `README.md`, any `AGENTS.md`, `git status`, and current source/tests; treat the worktree as authoritative and preserve user edits. Preserve HTML under `templates`, CSS/JS under `static`, and REST API boundaries. Both sandbox payment adapters and admin bKash callback-loss recovery are implemented but have not been exercised with merchant credentials; never enable live-money endpoints. Continue the remaining phase gates, including MongoDB/Redis live integration tests, quota reconciliation of historical tenants and manual review of TTL-ambiguous invitations, remaining first-page-only frontend lists, browser walkthrough, observability, and live container deployment. Add tests for business rules and cross-tenant security at every slice. Keep MongoDB as source of truth, Redis for bounded cache/quotas/revocation, and AI outside official scoring. AI routing code covers Gemini drafts, Groq tutor, and Ollama local, but no live calls have been verified; do not claim AI complete. Do not use or expose real credentials; the user will add keys near the end. Commit locally only when a slice's acceptance gates are genuinely met, with a phase-specific message; never label the baseline or partial work as a completed phase. Update this handoff file as evidence changes, and report precise remaining gaps instead of claiming completion prematurely.
## Product-quality audit (payment cancellation, invitations and demo persistence)

The latest audit is recorded in `PAYMENT_EMAIL_MESSAGING_AUDIT.md`. Sandbox gateways now open in a new tab, unfinished orders can be cancelled safely, and cancelled orders cannot be reconciled or grant entitlements. Invitation UI now explains SMTP versus local-console delivery, quota reconciliation copy distinguishes read-only checks from audited repairs, and demo role controls reload after logout. The Google `g_state` message remains harmless external GIS cookie-parser noise; global cookie parsing is intentionally not relaxed.
## Profile image option

Users can add or remove an optional profile image from Profile & settings. Uploads are authenticated, demo profiles are locked, and PNG/JPEG input is resized to 256px and compressed below 120 KB before being stored in the separate `user_avatars` collection. No public or unauthenticated image URL is exposed.
## Google one-click sign-in

Google sign-in now creates new verified Gmail/Workspace accounts directly and automatically links an existing verified JachAI account when the authoritative Google email matches. Password entry is not required for that first Google sign-in. Disabled, unverified, non-authoritative-domain, and conflicting Google identities remain rejected. Details are in `GOOGLE_SIGN_IN.md`.
