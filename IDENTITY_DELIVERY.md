# Identity, organizations and mail — 2026-10-07

The current `main` branch contains the identity/org code fixes; this is **not** a live-service acceptance report. Keep work on `auth`, `organization`, `mail` and `messaging` with one writer at a time.

## Delivered code

- Google ID-token audience/authorized-party validation: `c9536cf`.
- Password-reset responses distinguish queued mail from delivered mail: `47b5946`; safe validation field details: `6fa7efa`.
- Seven-day pending-account retention and link validity: `92ccdb0`, `4b30adf`.
- Join-code counts now include completed joins only, with retryable partial outcomes: `eeef80f`.
- Concurrent verification resends use one current token per user; pre-migration links are invalidated: `db7d427`. An opt-in Mongo resend regression test was added in `d44be59`.
- Concurrent password-reset requests use one current token per user, and shared demo identities cannot edit their profile: `59fd1f9`.
- An uncertain owner-membership write no longer deletes a potentially created workspace; the owner workspace list attempts a missing-membership repair: `6d09c6e`.

## Verification and limits

- On the integrated `main` at `6d09c6e`, Java 21 `mvnw.cmd -q test` passed: 565 tests, 0 failures, 0 errors. The opt-in Mongo integration classes ran **zero** tests because `MONGODB_TEST_URI` was absent. Focused tests for the changes above passed before this full run.
- No SMTP message, real Google sign-in, Atlas retention/TTL, or Mongo concurrency test was executed in this environment. The application was not running on ports 8080 or 8095 when checked. Do not interpret queued mail as delivered mail.
- To verify without exposing secrets, provide a disposable `MONGODB_TEST_URI` whose database starts `jachai_it_` through the private process environment; configure `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` for a private SMTP test account; set `GOOGLE_CLIENT_ID` for the exact allowed local origin. Never paste values into this file, chat, or commits.
- Remaining live checks: register → inbox verification → login, resend/reset delivery and retry, Google sign-in/link, join code and invitation acceptance under real Mongo concurrency, seven-day cleanup/TTL, and membership/ownership recovery. See `PROJECT_TODO.md` and `ARCHITECTURE.md` §14 for the broader release gates.
