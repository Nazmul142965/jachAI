# JachAI system, deployment, and readiness

This is the active operations guide. Product rules are in [ARCHITECTURE.md](ARCHITECTURE.md), implementation boundaries in [BACKEND.md](BACKEND.md), and the current UI in [FRONTEND.md](FRONTEND.md). Completed test and branch reports are indexed in [docs/archive/completed/](docs/archive/completed/README.md). Historical evidence does not replace a check on the deployed revision.

## Demo access and isolated platform-admin preview

The six one-click demo roles are available when the demo access endpoint is enabled;
the current implementation does **not** require a special database name and does not
use `DEMO_SHARED_DATABASE` as an authorization gate. Do not restore a
`jachai_demo_*` database restriction or make `DEMO_SHARED_DATABASE` mandatory.
`DEMO_SHARED_DATABASE` is retained only as a backwards-compatible configuration name
and has no effect on access.

For local verification use `SPRING_PROFILES_ACTIVE=demo` (or `local`) and a
disposable database such as `DEMO_MONGODB_DATABASE=jachai_demo_local`. For a hosted
preview set `DEMO_PUBLIC_ACCESS=true` and use a public HTTPS `APP_PUBLIC_BASE_URL`.
Local requests remain loopback-only when public access is false. Demo users and
organizations use reserved `demo-account-*`, `demo-org-*`, and
`@demo.jachai.invalid` identifiers; they are seeded through normal services and can
access only the two demo organizations. They cannot change their profile/password,
link Google, or use password reset. All customer organizations remain protected by
the existing membership and tenant checks.

This is a shared production data store, not a separate demo database. Anyone with the public URL can change demo workspace data, so do not place customer records in the demo organizations and do not describe this as a private faculty account. Payment credentials and real-money endpoints remain disabled for the demo path.

The seventh, platform-admin preview role is available only with the `demo` profile
on a database named `jachai_demo_*`. It has no organization membership and uses
`demo-account-platform-admin` as its bootstrap admin ID. Never put customer data
in that database: anyone with the public preview URL can sign in as this admin.
The six workspace roles remain available under their existing access rules.

For a Render preview, deploy the repository Dockerfile as a Web Service and set
`SPRING_PROFILES_ACTIVE=demo`, `SERVER_ADDRESS=0.0.0.0`,
`DEMO_MONGODB_DATABASE=jachai_demo_faculty`, `DEMO_PUBLIC_ACCESS=true`, and
`APP_PUBLIC_BASE_URL` to the service's HTTPS URL. Keep `MONGODB_URI`, `JWT_SECRET`,
and mail credentials in Render environment secrets, never GitHub. Set
`MAIL_HOST=smtp.resend.com`, `MAIL_PORT=2587`, `MAIL_USERNAME=resend`,
`MAIL_FROM=noreply@mail.nazmulhsn.me`, and `MAIL_PASSWORD` to the Resend API key
if sending mail from this preview. Verify the live connection before assuming
email delivery; Render Free does not guarantee an always-awake background worker.

`DEMO_PREVIEW_URL` is no longer needed. The sign-in page discovers roles from
`/api/v1/demo/accounts` and each button posts to `/api/v1/demo/login/{key}` on the same
origin, then creates the normal JachAI session and routes to the role dashboard. If
public demo access is disabled, the endpoint remains available only from loopback;
hosted public access requires `DEMO_PUBLIC_ACCESS=true` and a valid HTTPS origin.

Local live verification on 2026-10-08 passed on `http://127.0.0.1:8080/` using
`SPRING_PROFILES_ACTIVE=demo` and the disposable `jachai_demo_local` database. Hosted
verification still requires the actual public HTTPS URL and a controlled deployment
with `DEMO_PUBLIC_ACCESS=true`; local Chrome evidence is not hosted evidence.

## Build

Use Java 21 to run `./mvnw test` before building. The repository has a multi-stage Dockerfile that builds the Spring Boot JAR, then runs it as an unprivileged user on a Java 21 JRE. The container build intentionally skips tests; test the same revision before publishing an image.

```text
docker build -t jachai:demo .
docker run --rm --env-file .env.production -p 8080:8080 jachai:demo
```

Create `.env.production` locally; it is Git-ignored and must not be uploaded to the repository or copied into the image. Use a secret manager for a hosted deployment. The required production values are `MONGODB_URI` (cluster and credentials only; no application database in the URI), `JWT_SECRET` (at least 32 random bytes), a Redis connection, `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, and `APP_PUBLIC_BASE_URL` set to the public HTTPS origin. `application-prod.properties` selects database `jachai_prod`; override only with `MONGODB_DATABASE` when intentionally deploying a different production database. For Redis, use `REDIS_HOST`/`REDIS_PORT` plus `REDIS_USERNAME`/`REDIS_PASSWORD` and `REDIS_SSL_ENABLED=true` when the host requires them, or set `SPRING_DATA_REDIS_URL` (which overrides the host/port/credentials). Never commit a Redis URL containing a password. `MAIL_PORT` defaults to 587. A separate, stable `MAIL_JOB_ENCRYPTION_KEY` is strongly recommended; changing it makes queued messages unreadable. Set `SPRING_PROFILES_ACTIVE=prod` if starting the JAR without the Dockerfile.

The developer's IntelliJ run configuration currently has Gmail SMTP on port 587 with the `MAIL_*` credentials. Those credentials are for the existing SMTP transport; this codebase has no Brevo or Resend HTTPS-mail API transport or API key configured. Do not claim one is ready. [Render Free blocks outbound SMTP ports 25, 465, and 587](https://render.com/docs/free), so copying the current Gmail settings to Render Free will not deliver verification/reset/invitation mail. Before using that host for real sign-ups, either implement and test an HTTPS mail API with its own verified sender and credential, or choose a host whose outbound SMTP policy permits the existing Gmail transport. A healthy app or queued outbox job does not prove delivery; verify an actual inbox and the final `email_jobs` state.

### Render deployment checklist

The container itself is correct: `SPRING_PROFILES_ACTIVE=prod` and `JAVA_TOOL_OPTIONS` come from the Dockerfile, and `EXPOSE 8080` matches the port Spring listens on. Everything that remains is environment. A Render environment variable overrides the Dockerfile's `ENV` of the same name, so do not re-declare `SPRING_PROFILES_ACTIVE` or `JAVA_TOOL_OPTIONS` there unless you intend to replace them; an earlier deploy failed because a re-declared `JAVA_TOOL_OPTIONS` had lost its leading dash.

Eight variables have no default in `application-prod.properties` and the application refuses to start without them. `RequiredSettingsGuard` now reports all eight at once, before any bean is built, rather than surfacing one per deploy as whichever bean happened to need it first:

`MONGODB_URI`, `JWT_SECRET`, `APP_PUBLIC_BASE_URL`, `REDIS_HOST`, `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`.

- **`APP_PUBLIC_BASE_URL`** is the public HTTPS origin with no trailing slash — the custom domain once one is attached, otherwise `https://<service>.onrender.com`. It is not cosmetic: email links, certificate verification links and payment callbacks are built from it, and both gateway adapters reject a non-loopback HTTP origin.
- **`REDIS_HOST`** needs an actual Redis. Create a Render Key Value instance and use its internal connection details; set `REDIS_PASSWORD` and `REDIS_SSL_ENABLED=true` only if the chosen connection requires them. Production enables rate limits, token revocation and AI quotas by default, and all three fail closed: without a reachable Redis, sign-in answers 503 rather than skipping protection.
- Paste `MONGODB_URI` as a single unescaped line. A shell-escaped copy produced `unsupported option 'compressors\'` and `'appname\'` warnings from the driver — the trailing backslashes are the giveaway.

`TRUSTED_PROXY_CIDRS` stays empty unless the platform's proxy addresses are known. Empty is the safe default, but behind Render's load balancer every request then shares one socket peer, so IP-scoped limits become a single bucket for all users; email-scoped login limits are unaffected. Determine the actual peer address from a live request before trusting any range, and keep `server.forward-headers-strategy=none` — startup rejects an override.

#### Custom domain

Add the domain under the service's **Settings → Custom Domains**, then create a `CNAME` for the subdomain pointing at `<service>.onrender.com` in the DNS zone. Render issues the certificate once the record resolves. After it is live, three things must be changed together or the deployment is half-migrated: set `APP_PUBLIC_BASE_URL` to the new origin, add that origin to the Google OAuth client's **Authorised JavaScript origins**, and update the sandbox payment callbacks. Old certificate-verification links keep pointing at the previous origin.

#### Mail on Render Free

[Render Free blocks outbound SMTP on ports 25, 465 and 587](https://render.com/docs/free), so the old Gmail transport cannot deliver from that host. This deployment does not use it: `MAIL_HOST=smtp.resend.com` on **port 2587**, which Resend offers precisely because the standard ports are commonly blocked, and which Render does not block. `EmailTransportConfig` enforces the rest of that pairing — `MAIL_USERNAME=resend` and a `MAIL_FROM` on a domain verified in Resend, never a Gmail address.

Two things still have to be true outside this repository: the `MAIL_FROM` domain is verified in Resend, and its API key is live. Neither is visible from the application, so verify one real inbox and the final `email_jobs` state after the first deploy. A queued row proves nothing.

### Free hosting path using the credentials already configured locally

For an always-on faculty preview without replacing Gmail SMTP, the best candidate to **test**, not a verified deployment, is an [Oracle Cloud Always Free Ampere A1 VM](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm). The current free allowance is equivalent to 2 OCPUs and 12 GB RAM, enough to allocate separate memory to the Spring Boot app and a local Redis service while keeping MongoDB on Atlas. Oracle documents an outbound block on SMTP port 25; it does not establish that this application's Gmail port 587 and authentication will succeed from a newly created VM. Test TCP 587, STARTTLS, actual inbox delivery, and `email_jobs` before declaring mail ready. Use a stable hostname with HTTPS, set `APP_PUBLIC_BASE_URL` to it, and update Google OAuth origin and sandbox payment callbacks. Oracle requires a payment card to create an account, Always Free shapes can be out of capacity, and resources outside the Always Free allowance can incur charges. Do not create or upgrade an account or deploy against customer data without the owner's approval.

Azure is an alternative for a **time-limited** trial: its [free Linux VM allowance lasts 12 months](https://azure.microsoft.com/en-us/free/), while [authenticated SMTP on port 587 is not blocked](https://learn.microsoft.com/en-us/troubleshoot/azure/virtual-network/troubleshoot-outbound-smtp-connectivity). Azure's always-free App Service has a one-hour-per-day quota, so it is not a substitute for an always-on JachAI instance. Prefer Oracle A1 for a longer-lived free VM if an eligible instance is available; use Azure's free VM only with its end date and billing limits explicitly tracked. Neither option has been provisioned or tested for this app yet.

For a **temporary** faculty walkthrough without a cloud account, a [Cloudflare Quick Tunnel](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/) can publish the already-running local port 8080 through a random HTTPS URL, so the app continues using the locally configured Atlas and Gmail SMTP connections. This is not an always-on deployment: the PC and tunnel must stay running, the URL changes on restart, and each changed URL requires updated `APP_PUBLIC_BASE_URL`, Google OAuth origin, and payment callbacks before those journeys can be tested. Do not expose an unreviewed production database or admin account through a quick tunnel. `cloudflared` is not currently installed on the developer machine.

Provider settings are optional until those features are tested: Gemini, Groq, Google sign-in, and the two sandbox merchant credential sets. The production profile enables Redis cache, token-revocation acceleration, and request limits by default. MongoDB remains the durable authority for accounts and revoked access tokens. Use only direct bKash and SSLCOMMERZ sandbox credentials; the adapters are not configured for live payments.

## Optional settings added later

| Variable | Default | What it does |
| --- | --- | --- |
| `BILLING_DEMO_CHECKOUT` | `true` | Turns on the built-in demo checkout (pay, plan changes, receipt email, admin log). Set `false` to use only the bKash and SSLCOMMERZ sandboxes. No real money is taken either way. |
| `AI_QUESTION_FALLBACK` | `auto` | If the main AI provider for question drafts fails or is slow, try the other configured provider (Gemini to Groq). `auto` does this when both keys exist. |
| `AI_TUTOR_FALLBACK` | `auto` | The same failover for the AI Tutor. |
| `AI_PRIMARY_TIMEOUT_SECONDS` | `25` | How long to wait for the main AI provider before switching to the fallback. |

`GET /api/v1/health` also sends a small ping to MongoDB (at most every 30 seconds) so the database connection stays warm. It always answers `UP`, so a database problem never makes the platform restart the app. Point an uptime monitor at this URL, not at `/actuator/health`.

## Health probes

### Profile image storage

Profile images are optional. The application decodes the uploaded PNG/JPEG, rejects files over 5 MB or dimensions over 6000px, resizes the longest edge to 256px, converts to JPEG, and rejects compressed output over 120 KB. The compressed bytes are stored in a separate `user_avatars` MongoDB document, not inside the user record or a filesystem volume. The authenticated account response contains the small data URL, so the image is visible after login without a public image endpoint. This keeps image storage bounded, but operators should still monitor Atlas storage and users should remove unused images.

An authenticated platform admin can poll `GET /api/v1/platform/operations/summary` for global backlog counts. Alert on nonzero failed mail jobs, verified-but-unapplied sandbox orders older than five minutes, or quota repair requests without completion. Overdue pending mail is measured after a fifteen-minute grace; open sandbox checkouts are considered old after 24 hours and may simply be abandoned. The endpoint does not reveal recipients, orders, or credentials. MongoDB/Redis readiness needs separate monitoring; these counts require MongoDB to be reachable.

An authenticated platform admin can poll `GET /api/v1/platform/operations/storage` for a database-level estimate of data bytes plus index bytes. It reports a warning at 400,000,000 bytes by default; set `ATLAS_STORAGE_WARNING_BYTES` to adjust that operational threshold. This is based on MongoDB `dbStats`, not Atlas's authoritative cluster quota/billing meter: use Atlas's own storage alerts as well, especially if the database is shared with other applications. A failed or unauthorized `dbStats` command returns a generic unavailable error rather than connection details. The endpoint has offline tests but has not yet run against the user's Atlas cluster.

- `GET /api/v1/health` and `GET /actuator/health/liveness` mean the process is responding. They do not prove MongoDB or Redis works.
- `GET /actuator/health/readiness` includes MongoDB and Redis in the production profile. An unhealthy dependency returns an unhealthy readiness response; do not route assessment traffic to that instance.
- Health details are hidden from anonymous callers. Only the health endpoint is exposed through Actuator.

Keep liveness independent of external services: restarting every instance when Atlas or Redis has an outage can make recovery worse. Configure the hosting platform's liveness and readiness HTTP probes separately on the application port (default 8080). Use a startup grace period suitable for cold MongoDB/Redis connections and index creation.

## Release gates still open

1. Run the opt-in `local,mongo-smoke` save/read/delete check against a non-production MongoDB, then verify indexes, TTL, tenant isolation, quota reservations, outbox retries, and audit repair against live MongoDB and Redis.
2. Verify SMTP delivery and recovery, Google sign-in, Gemini/Groq/Ollama requests, and both payment sandboxes with their own test credentials. SSLCOMMERZ checkout posts success/failure/cancel callbacks and an IPN to the configured HTTPS base URL; the server independently validates `val_id`, merchant transaction ID, exact BDT amount/currency, store ID, risk level, and bank transaction ID before crediting TEAM. Test a completed payment whose browser callback/IPN is lost: admin reconciliation must query by merchant transaction ID, validate the returned reference through the independent validation API, and credit TEAM once. A pending/failed payment or ambiguous query must not credit TEAM. Do not accept browser redirects as payment proof.
3. Walk the Academy and Talent workflows in a browser, including more than 20 rows, manual result release, certificate revocation, and stale-token handling. Also test the Platform section with a verified platform admin who owns no workspace, a regular user who must not see it, paged organization/order lists, a stale organization selection, and the repair confirmation/audit flow.
4. Reconcile historical quota buckets before claiming billing limits are production-safe. A platform admin can page `GET /api/v1/platform/organizations?page=N`, then for each organization inspect `GET /api/v1/platform/organizations/{id}/billing/usage-reconciliation` and deliberately call `POST` on the same path. Re-run `GET` afterward and retain reports for review. The POST only repairs gaps that durable records prove and stamps the acting admin on changed buckets; it can move a tenant above its current plan limit. Do not manually lower `AMBIGUOUS` or `DEFERRED_EXPIRED_INVITATIONS` counters: accepted email invitations create assignments without another charge, and TTL or old reissue timestamps can erase evidence. Review those cases against external records before any operator correction. This operator path and its compare-and-set behavior still need a live MongoDB test.
5. Verify trusted reverse-proxy client-IP handling with a live load balancer before relying on IP rate limits behind it. Monitor free-tier MongoDB storage, Redis availability, outbox failures, and payment-verification failures.

For quota migration, inspect `GET /api/v1/platform/organizations/{id}/billing/usage-reconciliation/audit?page=0` after each repair. A `REQUESTED` event is inserted before mutation and `COMPLETED` afterward. Missing completion or `OUTCOME_UNCONFIRMED` does not prove that no bucket changed; refresh the report and review before retrying. This append-only trail is not a MongoDB cross-document transaction.

The application health endpoints have smoke coverage, and the JAR can be packaged locally. The Dockerfile and production readiness group have not yet been exercised in a running container; that requires Docker and live service connections.

## Client IP behind a trusted reverse proxy

`TRUSTED_PROXY_CIDRS` is empty by default, so auth and public-certificate IP limits use the socket peer and ignore forwarded headers. If deploying behind a reverse proxy, set this variable to **only** the proxy addresses or narrow CIDRs that can connect directly to the app (comma-separated IPv4/IPv6 literals; no hostnames). The app must not be directly reachable from the public Internet when proxies are trusted. Keep `server.forward-headers-strategy=none`: application startup rejects an override because Spring/Tomcat rewriting the remote address would make the trust check meaningless. Do not add a `ForwardedHeaderFilter` or equivalent remote-address-rewriting middleware in front of these routes.

Configure the public edge proxy to **replace** any incoming `X-Forwarded-For`, not pass through a client-supplied value, and append the actual connecting client address. Each subsequent trusted proxy may append its own observed peer. The resolver checks the raw socket peer first; only a configured trusted peer can supply one `X-Forwarded-For` header. It walks the chain from right to left and chooses the first untrusted address. Missing, duplicate, oversized, or malformed headers fall back to the proxy's socket address (a shared, conservative rate-limit bucket). Other forwarding headers are ignored for rate-limit identity. Test the exact load-balancer/proxy chain and an attacker-supplied spoofed prefix before relying on these limits; no live proxy check has been performed yet.
