# Deployment and readiness checklist

This is a sandbox-only deployment path for evaluating JachAI. It is not a production release sign-off. Keep all gateway endpoints in sandbox mode and never commit real environment files.

## Build

Use Java 21 to run `./mvnw test` before building. The repository has a multi-stage Dockerfile that builds the Spring Boot JAR, then runs it as an unprivileged user on a Java 21 JRE. The container build intentionally skips tests; test the same revision before publishing an image.

```text
docker build -t jachai:demo .
docker run --rm --env-file .env.production -p 8080:8080 jachai:demo
```

Create `.env.production` locally; it is Git-ignored and must not be uploaded to the repository or copied into the image. Use a secret manager for a hosted deployment. The required production values are `MONGODB_URI`, `JWT_SECRET` (at least 32 random bytes), a Redis connection, `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, and `APP_PUBLIC_BASE_URL` set to the public HTTPS origin. For Redis, use `REDIS_HOST`/`REDIS_PORT` plus `REDIS_USERNAME`/`REDIS_PASSWORD` and `REDIS_SSL_ENABLED=true` when the host requires them, or set `SPRING_DATA_REDIS_URL` (which overrides the host/port/credentials). Never commit a Redis URL containing a password. `MAIL_PORT` defaults to 587. A separate, stable `MAIL_JOB_ENCRYPTION_KEY` is strongly recommended; changing it makes queued messages unreadable. Set `SPRING_PROFILES_ACTIVE=prod` if starting the JAR without the Dockerfile.

Provider settings are optional until those features are tested: Gemini, Groq, Google sign-in, and the two sandbox merchant credential sets. The production profile enables Redis cache, token-revocation acceleration, and request limits by default. MongoDB remains the durable authority for accounts and revoked access tokens. Use only direct bKash and SSLCOMMERZ sandbox credentials; the adapters are not configured for live payments.

## Health probes

- `GET /api/v1/health` and `GET /actuator/health/liveness` mean the process is responding. They do not prove MongoDB or Redis works.
- `GET /actuator/health/readiness` includes MongoDB and Redis in the production profile. An unhealthy dependency returns an unhealthy readiness response; do not route assessment traffic to that instance.
- Health details are hidden from anonymous callers. Only the health endpoint is exposed through Actuator.

Keep liveness independent of external services: restarting every instance when Atlas or Redis has an outage can make recovery worse. Configure the hosting platform's liveness and readiness HTTP probes separately on the application port (default 8080). Use a startup grace period suitable for cold MongoDB/Redis connections and index creation.

## Release gates still open

1. Run the opt-in `local,mongo-smoke` save/read/delete check against a non-production MongoDB, then verify indexes, TTL, tenant isolation, quota reservations, outbox retries, and audit repair against live MongoDB and Redis.
2. Verify SMTP delivery and recovery, Google sign-in, Gemini/Groq/Ollama requests, and both payment sandboxes with their own test credentials. Do not accept browser redirects as payment proof.
3. Walk the Academy and Talent workflows in a browser, including more than 20 rows, manual result release, certificate revocation, and stale-token handling.
4. Add quota backfill/reconciliation before claiming billing limits are production-safe. Accepted email invitations create assignments without an extra reservation, so blindly counting all assignments would double-count them. Older reissued invitation documents may have lost their initial creation date.
5. Verify trusted reverse-proxy client-IP handling with a live load balancer before relying on IP rate limits behind it. Monitor free-tier MongoDB storage, Redis availability, outbox failures, and payment-verification failures.

The application health endpoints have smoke coverage, and the JAR can be packaged locally. The Dockerfile and production readiness group have not yet been exercised in a running container; that requires Docker and live service connections.

## Client IP behind a trusted reverse proxy

`TRUSTED_PROXY_CIDRS` is empty by default, so auth and public-certificate IP limits use the socket peer and ignore forwarded headers. If deploying behind a reverse proxy, set this variable to **only** the proxy addresses or narrow CIDRs that can connect directly to the app (comma-separated IPv4/IPv6 literals; no hostnames). The app must not be directly reachable from the public Internet when proxies are trusted. Keep `server.forward-headers-strategy=none`: application startup rejects an override because Spring/Tomcat rewriting the remote address would make the trust check meaningless. Do not add a `ForwardedHeaderFilter` or equivalent remote-address-rewriting middleware in front of these routes.

Configure the public edge proxy to **replace** any incoming `X-Forwarded-For`, not pass through a client-supplied value, and append the actual connecting client address. Each subsequent trusted proxy may append its own observed peer. The resolver checks the raw socket peer first; only a configured trusted peer can supply one `X-Forwarded-For` header. It walks the chain from right to left and chooses the first untrusted address. Missing, duplicate, oversized, or malformed headers fall back to the proxy's socket address (a shared, conservative rate-limit bucket). Other forwarding headers are ignored for rate-limit identity. Test the exact load-balancer/proxy chain and an attacker-supplied spoofed prefix before relying on these limits; no live proxy check has been performed yet.
