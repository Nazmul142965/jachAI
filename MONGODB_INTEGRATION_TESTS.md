# MongoDB Integration Tests

Opt-in real-MongoDB tests for the billing/assessment foundation, plus the offline guard test that
protects them.

**Live local checkpoint (2026-10-03):** all five billing classes ran together against a loopback-only
MongoDB 8.0 standalone using a dedicated `jachai_it_*` database: **27 billing tests, 0 failures/errors/skips**.
The separate learner assignment-metadata tenant query also passed **1/1** against the same local
database. These are separate invocations, not a claimed 28-test combined run.
The first live run exposed stale test fixtures: certificate documents omitted unique-indexed fields,
and the `mail_due` assertion omitted `leaseUntil`. Those fixtures were corrected before the passing run.
This does not verify Atlas, replica-set behavior, actual asynchronous TTL expiry, or other services.

## Two suites, and how to tell them apart

| | Offline suite | Live MongoDB suite |
|---|---|---|
| Command | `.\mvnw.cmd -o test` (JDK 21) | same command **with `MONGODB_TEST_URI` set** |
| Requires MongoDB | no | yes, a dedicated `jachai_it_*` database |
| Classes | Unit and mock-based classes; check the current run for its count | five `billing.integration.*IntegrationTests` classes (**27 passed locally**) plus `AssessmentMetadataIntegrationTests` (**1 passed locally**) |
| What it proves | unit and mock-based behaviour | real index creation, CAS, concurrency, tenant-scoped reads |

### A green offline run does not mean the live tests passed

When `MONGODB_TEST_URI` is not set, `BaseMongoIntegrationTest` aborts each live container from its
`@BeforeAll` hook. Surefire records an aborted container as:

```text
Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
```

That is **0 executed tests**, and it is deliberately *not* reported as `Skipped: 1`. A run summary
that says `Skipped: 0` therefore says nothing about live MongoDB. Report live results per class, never
from the project-wide totals.

## Safety features

- **Dedicated database only**: the URI must name a database beginning with `jachai_it_`. The check runs
  before any client is created; a rejected URI never connects and is never echoed.
- **`MONGODB_TEST_URI` only**: the integration profile binds `spring.data.mongodb.uri` to
  `${MONGODB_TEST_URI}`. The application's own URIs come from `MONGODB_URI`
  (`application-local.properties`, `application-prod.properties`), so the integration profile cannot
  silently inherit a production database.
- **No unfiltered deletes**: cleanup deletes only by tracked `_id`. `deleteMany(new Document())` is
  never used and collections are never dropped.
- **Thread-safe tracking**: `createdDocumentIds` is a concurrent set, because reservation and
  reconciliation tests register IDs from worker threads.
- **Register before write**: an ID is tracked before the write it describes, so an interruption cannot
  leave an untracked document behind.
- **Credential-free failure reporting**: failures name the collection, the document ID and the
  exception type only. Connection strings and credentials are never printed.

## How the URI guard is ordered

The guard lives in `MongoTestUriGuard.requireDedicatedTestDatabase`. Four layers apply:

1. **Profile binding** — `src/test/resources/application-integration.properties` binds
   `spring.data.mongodb.uri=${MONGODB_TEST_URI}`. There is no fallback, so the integration profile can
   only ever point at the variable this guard validates.
2. **Test-classpath environment post-processor** — `MongoIntegrationEnvironmentGuard` is registered in
   `src/test/resources/META-INF/spring.factories`. Every Spring Boot context using the `integration`
   profile validates `MONGODB_TEST_URI` while preparing the environment, even if its test class does
   not inherit `BaseMongoIntegrationTest`.
3. **`@DynamicPropertySource`** (`BaseMongoIntegrationTest.bindDedicatedTestDatabase`) — Spring invokes
   dynamic property sources while it *customizes the environment*, which happens before the context is
   refreshed. The `MongoClient` bean is created and automatic index creation happens during refresh, so
   a rejected URI fails here, before any connection or index write.
4. **`@BeforeAll`** (`BaseMongoIntegrationTest.validateTestUriAndConnect`) — for the classes that build
   their own `MongoTemplate`, the URI is validated before `MongoClients.create` is reached.

The environment post-processor is test-only and executes before Mongo client/index creation during
Spring context startup. The base-class hooks remain defense in depth and cover classes constructing
their own clients. A future test that creates a Mongo client directly without either Spring's
`integration` profile or the base class would bypass these safeguards; do not add such a test.

`MongoTestUriGuardTests` proves the guard itself offline (missing URI, no database, non-test database,
malformed string, valid database, and that a rejected URI is never echoed). It needs no MongoDB.
`MongoIntegrationEnvironmentGuardTests` verifies the profile gate and the Spring factory registration.

## Prerequisites

- JDK 21 (user-level `JAVA_HOME` is now 21; an already-open shell may still have 17, so set it per command if needed)
- A MongoDB instance (local or Atlas) and a **dedicated** database named `jachai_it_*`

```powershell
$env:JAVA_HOME='C:\Users\nazmul\.jdks\ms-21.0.12.1'
$env:MONGODB_TEST_URI='mongodb://localhost:27017/jachai_it_billing'
```

Valid offline example: `mongodb://localhost:27017/jachai_it_billing`.
Atlas `mongodb+srv` URIs are supported when the hostname has real SRV records; a fake example
hostname cannot be used in the offline parser test because parsing may perform DNS lookups.
Rejected: `mongodb://localhost:27017/jachai`, `.../production`, a URI with no database, a malformed
string, or anything sourced from `MONGODB_URI`.

## Running

Offline (no MongoDB needed):

```powershell
$env:JAVA_HOME='C:\Users\nazmul\.jdks\ms-21.0.12.1'
.\mvnw.cmd -o test
```

Live, one class at a time (each class declares `@ActiveProfiles("integration")` itself, so
`-Dspring.profiles.active=integration` is **not** required — passing it would also switch the offline
tests onto the integration profile):

```powershell
$env:MONGODB_TEST_URI='mongodb://localhost:27017/jachai_it_billing'
.\mvnw.cmd -o test -Dtest=TenantScopedReadIntegrationTests
.\mvnw.cmd -o test -Dtest=EntitlementReservationCasIntegrationTests
.\mvnw.cmd -o test -Dtest=ReconciliationCasIntegrationTests
.\mvnw.cmd -o test -Dtest=IndexCreationIntegrationTests
.\mvnw.cmd -o test -Dtest=TtlIndexBehaviorIntegrationTests
.\mvnw.cmd -o test -Dtest=AssessmentMetadataIntegrationTests
```

Tests remove only their own tracked documents. Do not drop a database as routine test cleanup; an
operator may remove a confirmed disposable database separately if needed. The TTL monitor is
asynchronous, so the TTL class should be run on its own and no test waits for actual expiry.

## Coverage

### MongoTestUriGuardTests (offline)

- missing/blank URI rejected, URI with no database rejected, non-`jachai_it_` database rejected,
  malformed URI rejected, valid dedicated database accepted, rejected URI never echoed

### TenantScopedReadIntegrationTests (own `MongoTemplate`)

- usage bucket reads scoped to tenant; `MongoTemplate` queries scoped to tenant; aggregate counts
  scoped to tenant

### AssessmentMetadataIntegrationTests (real Spring Data repository)

- a batched `assessment_versions` lookup by IDs and organization returns only that organization's
  published metadata, even when the requested ID set also contains another tenant's version

### EntitlementReservationCasIntegrationTests (own `MongoTemplate`, real `EntitlementService`)

- `replaceUsage` CAS succeeds on a matching revision and fails on a wrong revision, wrong usage, or
  wrong organization
- legacy document without a revision is initialised to revision 1 under the same CAS
- the real reservation path (`EntitlementService.reserve`) inserts with `used=1, revision=1` and
  increments to `used=2, revision=2` on the second call
- 10 concurrent reservations all succeed within the FREE certificate limit (30), with
  `used == 10` and `revision == 10`
- 5 concurrent reconciles: **one attempt each against the same starting revision**, so exactly one can
  win; the winner applies the reconciled usage and advances the revision exactly once

### ReconciliationCasIntegrationTests (own `MongoTemplate`, real `EntitlementService`)

- backfill CAS against a real certificate count
- 5 concurrent reconciles, one attempt each at the fixed starting revision: exactly one winner, final
  usage equals the recorded count, revision advances once
- reconciliation racing a **real reservation** (`entitlementService.reserve`) against the same,
  already-existing bucket: the reservation must succeed, reconciliation must succeed at least once
  within its retry budget, usage must never fall below the recorded certificate count, and the revision
  must advance
- deterministic ABA check: usage is written back to its original value under a higher revision, and a
  stale write carrying the original revision must still be rejected. All writes share one timestamp, so
  monotonic revision — not time — is what rejects the stale write
- idempotency: a second CAS carrying the consumed revision fails and leaves the revision unchanged
- legacy document without a revision initialised under CAS

### IndexCreationIntegrationTests (`@SpringBootTest`, application context)

- duplicate `_id` insert fails with `DuplicateKeyException`
- the application-declared TTL index on `expiresAt` exists with `expireAfterSeconds = 0`
- the default `_id` index exists

### TtlIndexBehaviorIntegrationTests (`@SpringBootTest`, application context)

- the application-declared TTL index exists and is configured with `expireAfterSeconds = 0`
- documents with a future `expiresAt` persist, documents with a null `expiresAt` are not subject to TTL,
  and multiple documents with different expiry times coexist

This class starts the application context itself, so index creation comes from the application's own
`UsageBucket` mapping and the class passes when run alone. It previously asserted the same index facts
while relying on `IndexCreationIntegrationTests` having run first in the same JVM; that ordering was
never configured anywhere.

## Concurrency test design

`replaceUsage` matches on `_id`, `organizationId`, `used` and `revision`, and it does **not** reject an
update that leaves `used` unchanged — it still increments `revision`. Two consequences shape the tests:

- **"Exactly one winner" is only provable with a single attempt per worker at a fixed revision.** A
  retry loop that re-reads the revision would legitimately succeed again on a no-op update, so an
  `assertEquals(1, successCount)` assertion over a retry loop would be timing-dependent. The one-winner
  tests therefore issue one attempt each against the starting revision.
- **Convergence tests assert convergence**, not a single winner: `concurrentReservationsAreSerializedByCas`
  asserts that every reservation succeeds exactly once and that `used` and `revision` both equal the
  worker count.

Worker exceptions are always collected and asserted empty, so a swallowed failure cannot pass as
success.

## Test data and cleanup

- IDs are `{prefix}-{UUID}` (`generateTestId`); bucket IDs are `{organizationId}:{METRIC}:{window}`,
  matching `UsageWindow`, for example `org-<uuid>:CERTIFICATE:2026-10`.
- Bucket IDs used by the reservation tests are derived from the **current UTC month**, because that is
  the window `UsageWindow.of` computes at runtime. A run that spans a month boundary can therefore
  create a second bucket; the returned ID is tracked as well, so nothing leaks.
- `@AfterEach` deletes every tracked ID. One failing delete no longer abandons the rest: all IDs are
  attempted, then the failures are reported together. The tracker is only cleared after the attempts.
- Cleanup assumes string `_id` values. Every ID used here is a string; an `ObjectId` `_id` would delete
  nothing and would not raise an error.

## Known limits and follow-ups

- The concurrent reservation test forces reconciliation to use a pre-reservation snapshot. Its stale
  revision must lose the CAS, leaving the reservation counted above the two durable certificates.
  This tests the store-level race, not the full reconciliation service's grace-period policy.
- **Nothing here verifies live behaviour yet.** No live run has happened; the five classes have executed
  0 tests each.
- **Production follow-up (not part of this test slice):** `EntitlementService.reserve` upserts with
  `findAndModify`; when two first-ever reservations race, the loser can surface as a duplicate-key
  failure and is currently converted into `PLAN_LIMIT_REACHED` even though the limit was not reached.
  That is a production-code race to examine in its own slice.
- Extraction/TTL timing is never asserted, only configuration and persistence; the TTL monitor is not
  synchronous.

## Troubleshooting

- **Live classes report 0 tests**: `MONGODB_TEST_URI` is not set, or the database name does not start
  with `jachai_it_`. Check the failure message — it never contains the URI.
- **TTL index assertions fail**: the context must start, and
  `spring.data.mongodb.auto-index-creation=true` must be in effect for the integration profile. Check
  that the connection user may create indexes.
- **Connection errors**: verify the instance is reachable and the credentials are correct, without
  pasting the URI anywhere.

## Profile configuration

`src/test/resources/application-integration.properties`:

```properties
spring.data.mongodb.uri=${MONGODB_TEST_URI}
spring.data.mongodb.auto-index-creation=true
app.jwt.secret=test-only-32-byte-key-for-jachai-integration-tests-123456
```

The offline test profile (`application-test.properties`) excludes MongoDB auto-configuration.
