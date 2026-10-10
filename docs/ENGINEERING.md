# How JachAI is built

JachAI is a technical-assessment product for Academy learning and Talent hiring. The same account can practise privately, take an assigned assessment, or work for an organization. This page records the tools we chose, the problems the implementation exposed, and the decisions we made. It describes the current code, not a claim that every external service has been verified in production.

The public version of this story appears at `/engineering.html` on a running JachAI instance. Product rules live in [ARCHITECTURE.md](../ARCHITECTURE.md); deployment requirements live in [SYSTEM.md](../SYSTEM.md).

## The stack

| Layer | What we use | Why |
| --- | --- | --- |
| Server | Java 21, Spring Boot, Spring Security, Spring Data, Thymeleaf | One modular backend serves both the REST API and same-origin pages. Services make the authorization decisions. |
| Interface | HTML, CSS, and JavaScript with a small hash router | One signed-in workspace shell can switch between personal work and Academy/Talent workspaces without maintaining two production dashboards. A separate React practice pilot remains research, not the shipping UI. |
| Durable data | MongoDB | Stores accounts, organizations, assessment versions, attempts, email jobs, payment orders, audit events, and practice history. |
| Coordination | Redis | Production rate limits, atomic quota reservations, token-revocation acceleration, and published-version caching. Durable truth remains in MongoDB. |
| Identity and messaging | JWT access/rotated refresh sessions, Google Identity Services, encrypted MongoDB email outbox, SMTP | Supports verified-email accounts and optional Google sign-in while retaining retryable delivery. |
| AI and billing | Configured AI providers; bKash and SSLCOMMERZ **sandbox** adapters | AI drafts and explains; staff approve official content. Payment status is confirmed server-side before a plan changes. No real-money endpoint is enabled. |
| Packaging and checks | Maven, Dockerfile, Java tests, frontend journey tests, opt-in MongoDB integration tests | Reproducible builds and explicit evidence, with external-service checks kept separate from mocked tests. |

## Problems we had to solve

### A score must survive UI changes

An assessment is published as an immutable version. The server owns the attempt deadline and objective scoring; short answers wait for staff grading. A learner or candidate sees the result only when its release policy allows it. This prevents a browser timer, a changed question, or an AI response from silently changing the official outcome.

### One account has several contexts

Personal practice is not an organization score. Academy and Talent work is tenant-scoped, and one person may belong to more than one workspace with different roles. Service-layer membership checks decide access on every request; the navigation only reflects those decisions. Six shared demo identities make the roles visible for a walkthrough, but their passwords, profiles, avatars, and Google identities cannot be changed by visitors.

### Retries are ordinary, not exceptional

Email jobs, answer submissions, invitations, payment callbacks, and quota reservations can be retried after a lost response. The backend uses stable request/order identifiers, conditional writes, and re-reads of durable state to avoid duplicate effects. The email outbox checks that a link is still current before sending. Billing grants a plan only after independent sandbox validation, not because a browser returned from checkout.

### MongoDB and money need deliberate queries

An early repository-derived `BigDecimal` sum caused Spring Data to fail during startup. A custom aggregation repository replaced that query. Usage reconciliation now produces an auditable report instead of pretending every historical counter is certainly correct; ambiguous records are left for operator review rather than guessed downward.

### AI must have a bounded role

Generated questions remain drafts until a person approves them. The Tutor can explain a released result to an Academy learner or Talent candidate, but it cannot score an active exam. Personal practice has daily user and global generation limits, and failed generation should not spend a user's allowance.

## What has been checked, and what has not

The 2026-10-09 local release check recorded 660 Java tests with no failures, 151 frontend journey checks, and real-browser clicks through all six demo roles on a local instance. That evidence belongs to that revision and environment; it does not verify the deployed app. Real inbox delivery, Google OAuth on the final HTTPS origin, live AI-provider responses, production MongoDB/Redis readiness, and completed bKash/SSLCOMMERZ sandbox transactions still require their own checks. See [SYSTEM.md](../SYSTEM.md) for the release gates and the [archived check](archive/completed/RELEASE_CHECK_2026-10-09.md) for exact local evidence.
