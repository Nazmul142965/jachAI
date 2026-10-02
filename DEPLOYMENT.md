# Deployment and readiness checklist

This is a sandbox-only deployment path for evaluating JachAI. It is not a production release sign-off. Keep all gateway endpoints in sandbox mode and never commit real environment files.

## Build

Use Java 21 to run `./mvnw test` before building. The repository has a multi-stage Dockerfile that builds the Spring Boot JAR, then runs it as an unprivileged user on a Java 21 JRE. The container build intentionally skips tests; test the same revision before publishing an image.

```text
docker build -t jachai:demo .
docker run --rm --env-file .env.production -p 8080:8080 jachai:demo
```

Create `.env.production` locally; it is Git-ignored and must not be uploaded to the repository or copied into the image. Use a secret manager for a hosted deployment. The required production values are `MONGODB_URI`, `JWT_SECRET` (at least 32 random bytes), `REDIS_HOST`, `REDIS_PORT`, `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, and `APP_PUBLIC_BASE_URL` set to the public HTTPS origin. `MAIL_PORT` defaults to 587. A separate, stable `MAIL_JOB_ENCRYPTION_KEY` is strongly recommended; changing it makes queued messages unreadable. Set `SPRING_PROFILES_ACTIVE=prod` if starting the JAR without the Dockerfile.

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
5. Add trusted reverse-proxy client-IP handling before relying on IP rate limits behind a load balancer. Monitor free-tier MongoDB storage, Redis availability, outbox failures, and payment-verification failures.

The application health endpoints have smoke coverage, and the JAR can be packaged locally. The Dockerfile and production readiness group have not yet been exercised in a running container; that requires Docker and live service connections.
