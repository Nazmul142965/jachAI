# Deployment and readiness checklist

## Six-role demo on the production deployment

The six one-click roles can be enabled on the same hosted production deployment and Mongo database only with an explicit two-flag opt-in: `DEMO_PUBLIC_ACCESS=true` and `DEMO_SHARED_DATABASE=true`. The deployment must use a public HTTPS `APP_PUBLIC_BASE_URL`. Demo users and organizations use reserved `demo-account-*`, `demo-org-*`, and `@demo.jachai.invalid` identifiers; they are seeded through normal services and can access only the two demo organizations. They cannot change their profile/password, link Google, or use password reset. All customer organizations remain protected by the existing membership and tenant checks.

This is a shared production data store, not a separate demo database. Anyone with the public URL can change demo workspace data, so do not place customer records in the demo organizations and do not describe this as a private faculty account. Payment credentials and real-money endpoints remain disabled for the demo path.

`DEMO_PREVIEW_URL` is no longer needed for the same-deployment flow. The sign-in page discovers roles from `/api/v1/demo/accounts` and each button posts to `/api/v1/demo/login/{key}` on the same origin, then creates the normal JachAI session and routes to the role dashboard. If either flag is false, the endpoint is unavailable and no demo buttons are rendered.

Local live verification on 2026-10-08 passed on `http://127.0.0.1:8080/` using `SPRING_PROFILES_ACTIVE=demo` and the disposable `jachai_demo_local` database. Hosted verification still requires the actual public HTTPS URL and a controlled production deployment with both flags enabled; local Chrome evidence is not hosted evidence.

## Build

Use Java 21 to run `./mvnw test` before building. The repository has a multi-stage Dockerfile that builds the Spring Boot JAR, then runs it as an unprivileged user on a Java 21 JRE. The container build intentionally skips tests; test the same revision before publishing an image.

```text
docker build -t jachai:demo .
docker run --rm --env-file .env.production -p 8080:8080 jachai:demo
```

Create `.env.production` locally; it is Git-ignored and must not be uploaded to the repository or copied into the image. Use a secret manager for a hosted deployment. The required production values are `MONGODB_URI` (cluster and credentials only; no application database in the URI), `JWT_SECRET` (at least 32 random bytes), a Redis connection, `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, and `APP_PUBLIC_BASE_URL` set to the public HTTPS origin. `application-prod.properties` selects database `jachai_prod`; override only with `MONGODB_DATABASE` when intentionally deploying a different production database. For Redis, use `REDIS_HOST`/`REDIS_PORT` plus `REDIS_USERNAME`/`REDIS_PASSWORD` and `REDIS_SSL_ENABLED=true` when the host requires them, or set `SPRING_DATA_REDIS_URL` (which overrides the host/port/credentials). Never commit a Redis URL containing a password. `MAIL_PORT` defaults to 587. A separate, stable `MAIL_JOB_ENCRYPTION_KEY` is strongly recommended; changing it makes queued messages unreadable. Set `SPRING_PROFILES_ACTIVE=prod` if starting the JAR without the Dockerfile.

The developer's IntelliJ run configuration currently has Gmail SMTP on port 587 with the `MAIL_*` credentials. Those credentials are for the existing SMTP transport; this codebase has no Brevo or Resend HTTPS-mail API transport or API key configured. Do not claim one is ready. [Render Free blocks outbound SMTP ports 25, 465, and 587](https://render.com/docs/free), so copying the current Gmail settings to Render Free will not deliver verification/reset/invitation mail. Before using that host for real sign-ups, either implement and test an HTTPS mail API with its own verified sender and credential, or choose a host whose outbound SMTP policy permits the existing Gmail transport. A healthy app or queued outbox job does not prove delivery; verify an actual inbox and the final `email_jobs` state.

Provider settings are optional until those features are tested: Gemini, Groq, Google sign-in, and the two sandbox merchant credential sets. The production profile enables Redis cache, token-revocation acceleration, and request limits by default. MongoDB remains the durable authority for accounts and revoked access tokens. Use only direct bKash and SSLCOMMERZ sandbox credentials; the adapters are not configured for live payments.

## Health probes

An authenticated platform admin can poll `GET /api/v1/platform/operations/summary` for global backlog counts. Alert on nonzero failed mail jobs, verified-but-unapplied sandbox orders older than five minutes, or quota repair requests without completion. Overdue pending mail is measured after a fifteen-minute grace; open sandbox checkouts are considered old after 24 hours and may simply be abandoned. The endpoint does not reveal recipients, orders, or credentials. MongoDB/Redis readiness needs separate monitoring; these counts require MongoDB to be reachable.

An authenticated platform admin can poll `GET /api/v1/platform/operations/storage` for a database-level estimate of data bytes plus index bytes. It reports a warning at 400,000,000 bytes by default; set `ATLAS_STORAGE_WARNING_BYTES` to adjust that operational threshold. This is based on MongoDB `dbStats`, not Atlas's authoritative cluster quota/billing meter: use Atlas's own storage alerts as well, especially if the database is shared with other applications. A failed or unauthorized `dbStats` command returns a generic unavailable error rather than connection details. The endpoint has offline tests but has not yet run against the user's Atlas cluster.

- `GET /api/v1/health` and `GET /actuator/health/liveness` mean the process is responding. They do not prove MongoDB or Redis works.
- `GET /actuator/health/readiness` includes MongoDB and Redis in the production profile. An unhealthy dependency returns an unhealthy readiness response; do not route assessment traffic to that instance.
- Health details are hidden from anonymous callers. Only the health endpoint is exposed through Actuator.

Keep liveness independent of external services: restarting every instance when Atlas or Redis has an outage can make recovery worse. Configure the hosting platform's liveness and readiness HTTP probes separately on the application port (default 8080). Use a startup grace period suitable for cold MongoDB/Redis connections and index creation.

## Release gates still open

1. Run the opt-in `local,mongo-smoke` save/read/delete check against a non-production MongoDB, then verify indexes, TTL, tenant isolation, quota reservations, outbox retries, and audit repair against live MongoDB and Redis.
2. Verify SMTP delivery and recovery, Google sign-in, Gemini/Groq/Ollama requests, and both payment sandboxes with their own test credentials. For SSLCOMMERZ, test a completed payment whose browser callback/IPN is lost: admin reconciliation must query by merchant transaction ID, validate the returned reference through the independent validation API, and credit TEAM once. A pending/failed payment or ambiguous query must not credit TEAM. Do not accept browser redirects as payment proof.
3. Walk the Academy and Talent workflows in a browser, including more than 20 rows, manual result release, certificate revocation, and stale-token handling. Also test the Platform section with a verified platform admin who owns no workspace, a regular user who must not see it, paged organization/order lists, a stale organization selection, and the repair confirmation/audit flow.
4. Reconcile historical quota buckets before claiming billing limits are production-safe. A platform admin can page `GET /api/v1/platform/organizations?page=N`, then for each organization inspect `GET /api/v1/platform/organizations/{id}/billing/usage-reconciliation` and deliberately call `POST` on the same path. Re-run `GET` afterward and retain reports for review. The POST only repairs gaps that durable records prove and stamps the acting admin on changed buckets; it can move a tenant above its current plan limit. Do not manually lower `AMBIGUOUS` or `DEFERRED_EXPIRED_INVITATIONS` counters: accepted email invitations create assignments without another charge, and TTL or old reissue timestamps can erase evidence. Review those cases against external records before any operator correction. This operator path and its compare-and-set behavior still need a live MongoDB test.
5. Verify trusted reverse-proxy client-IP handling with a live load balancer before relying on IP rate limits behind it. Monitor free-tier MongoDB storage, Redis availability, outbox failures, and payment-verification failures.

For quota migration, inspect `GET /api/v1/platform/organizations/{id}/billing/usage-reconciliation/audit?page=0` after each repair. A `REQUESTED` event is inserted before mutation and `COMPLETED` afterward. Missing completion or `OUTCOME_UNCONFIRMED` does not prove that no bucket changed; refresh the report and review before retrying. This append-only trail is not a MongoDB cross-document transaction.

The application health endpoints have smoke coverage, and the JAR can be packaged locally. The Dockerfile and production readiness group have not yet been exercised in a running container; that requires Docker and live service connections.

## Client IP behind a trusted reverse proxy

`TRUSTED_PROXY_CIDRS` is empty by default, so auth and public-certificate IP limits use the socket peer and ignore forwarded headers. If deploying behind a reverse proxy, set this variable to **only** the proxy addresses or narrow CIDRs that can connect directly to the app (comma-separated IPv4/IPv6 literals; no hostnames). The app must not be directly reachable from the public Internet when proxies are trusted. Keep `server.forward-headers-strategy=none`: application startup rejects an override because Spring/Tomcat rewriting the remote address would make the trust check meaningless. Do not add a `ForwardedHeaderFilter` or equivalent remote-address-rewriting middleware in front of these routes.

Configure the public edge proxy to **replace** any incoming `X-Forwarded-For`, not pass through a client-supplied value, and append the actual connecting client address. Each subsequent trusted proxy may append its own observed peer. The resolver checks the raw socket peer first; only a configured trusted peer can supply one `X-Forwarded-For` header. It walks the chain from right to left and chooses the first untrusted address. Missing, duplicate, oversized, or malformed headers fall back to the proxy's socket address (a shared, conservative rate-limit bucket). Other forwarding headers are ignored for rate-limit identity. Test the exact load-balancer/proxy chain and an attacker-supplied spoofed prefix before relying on these limits; no live proxy check has been performed yet.
