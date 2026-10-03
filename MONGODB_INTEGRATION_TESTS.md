# MongoDB Integration Tests

This document describes the opt-in real MongoDB integration tests for the assessment/billing foundation.

## Purpose

These integration tests verify real MongoDB behavior that unit tests cannot prove:

- Tenant-scoped reads across collections
- Required unique and TTL index creation (application-created via UsageBucket mapping)
- Entitlement reservation with compare-and-swap (CAS) under concurrent writes
- Revision-based reconciliation CAS under concurrent writes
- TTL index configuration (not immediate deletion timing)

These tests use a separate test database and never touch the application database or existing user records.

## Safety Features

- **ID-based cleanup**: Tests track all created document IDs and clean up only those IDs
- **No unfiltered deletes**: Never use `deleteMany(new Document())` - only delete by tracked IDs
- **No collection drops**: Tests never drop collections; they use unique document IDs instead
- **URI validation**: Uses MongoDB URI parser to validate `MONGODB_TEST_URI` format
- **Database prefix enforcement**: Tests refuse to run unless database name begins with `jachai_it_`
- **Unique test IDs**: All test documents use unique IDs with timestamps and thread IDs
- **Connection safety**: Connection strings and credentials are never printed

## Prerequisites

### MongoDB Test Database

You must have a MongoDB instance (local or Atlas) and set the `MONGODB_TEST_URI` environment variable.

**Critical requirement**: The URI must point to a dedicated database whose name begins with `jachai_it_`.

Example valid URIs:
- `mongodb://localhost:27017/jachai_it_billing`
- `mongodb+srv://user:pass@cluster0.example.net/jachai_it_assessment?retryWrites=true&w=majority`

Invalid URIs (tests will refuse to run):
- `mongodb://localhost:27017/jachai` (does not begin with `jachai_it_`)
- `mongodb://localhost:27017/production` (production database)
- Using `MONGODB_URI` instead of `MONGODB_TEST_URI`
- Malformed URIs (will fail URI parser validation)

### Never Use Application Database

- Do not set `MONGODB_URI` for these tests
- Do not use your production or development database
- Create a dedicated test database with the `jachai_it_` prefix

## Running the Tests

### Offline Test Suite (No MongoDB Required)

The normal test suite runs without MongoDB and uses mocks:

```bash
mvn test
```

Or with the test profile explicitly:

```bash
mvn test -Dspring.profiles.active=test
```

This runs all existing unit tests and mock-based tests. The integration tests are skipped when `MONGODB_TEST_URI` is not set.

### Integration Test Suite (Requires MongoDB)

Set the `MONGODB_TEST_URI` environment variable and run with the integration profile:

**Windows PowerShell:**
```powershell
$env:MONGODB_TEST_URI="mongodb://localhost:27017/jachai_it_billing"
mvn test -Dspring.profiles.active=integration
```

**Windows CMD:**
```cmd
set MONGODB_TEST_URI=mongodb://localhost:27017/jachai_it_billing
mvn test -Dspring.profiles.active=integration
```

**Linux/macOS:**
```bash
export MONGODB_TEST_URI="mongodb://localhost:27017/jachai_it_billing"
mvn test -Dspring.profiles.active=integration
```

### Running Specific Integration Test Classes

To run only the integration tests for a specific area:

```bash
mvn test -Dspring.profiles.active=integration -Dtest=TenantScopedReadIntegrationTests
mvn test -Dspring.profiles.active=integration -Dtest=IndexCreationIntegrationTests
mvn test -Dspring.profiles.active=integration -Dtest=EntitlementReservationCasIntegrationTests
mvn test -Dspring.profiles.active=integration -Dtest=TtlIndexBehaviorIntegrationTests
mvn test -Dspring.profiles.active=integration -Dtest=ReconciliationCasIntegrationTests
```

### Running All Integration Tests

```bash
mvn test -Dspring.profiles.active=integration -Dtest="**/integration/**"
```

## Test Coverage

### TenantScopedReadIntegrationTests

Verifies that MongoDB queries properly enforce tenant isolation:
- Usage bucket reads are scoped to tenant
- MongoTemplate queries enforce tenant isolation
- Aggregate queries are tenant-scoped

### IndexCreationIntegrationTests

Verifies application-created index configuration:
- Unique index on `_id` prevents duplicate inserts
- Application-created TTL index is configured on `expiresAt` field
- TTL index configuration (expireAfterSeconds = 0)
- Application-created `_id` index exists

**Note**: These tests verify indexes created by the application's UsageBucket mapping, not manually created indexes.

### EntitlementReservationCasIntegrationTests

Verifies compare-and-swap behavior for entitlement reservations:
- Replace usage with revision CAS succeeds when revision matches
- Replace usage fails when revision does not match
- Replace usage fails when usage does not match
- Replace usage fails when organizationId does not match
- Legacy bucket without revision initializes revision on first replace
- Real reservation path (EntitlementService.reserve) increments usage atomically
- Concurrent reservations are serialized by CAS with exact success/failure counts
- Concurrent reconcile operations are serialized by revision

### TtlIndexBehaviorIntegrationTests

Verifies TTL index behavior:
- TTL index is present on entitlement_usage collection
- TTL index configuration expires at 0 seconds
- Document with future expiresAt persists
- Document with null expiresAt is not subject to TTL
- Multiple documents with different expiry times

**Note**: These tests verify TTL index configuration and presence. They do not assume MongoDB deletes documents immediately at expiry (TTL monitor runs periodically). Tests only verify configuration and that documents with future expiry persist.

### ReconciliationCasIntegrationTests

Verifies reconciliation with CAS under concurrent writes:
- Reconciliation backfill with revision CAS
- Concurrent reconcile operations prevent lost updates (exact success count)
- Reconciliation with concurrent reservation
- Same-millisecond ABA prevented by monotonic revision
- Reconciliation idempotency with revision
- Reconciliation with legacy document without revision

**Note**: Concurrency tests use CompletableFuture for deterministic synchronization and surface worker exceptions.

## Test Data Cleanup

Integration tests create only uniquely identified test data using timestamps and thread IDs:

- Organization IDs: `{prefix}-{timestamp}-{threadId}`
- Bucket IDs: `{orgId}:{metric}:{window}` with unique orgId
- Document IDs: `{prefix}-{timestamp}-{threadId}`

Each test tracks all created document IDs via `trackDocumentId()`. The base class's `@AfterEach` method cleans up only tracked IDs using `deleteOne(new Document("_id", id))`. No unfiltered `deleteMany()` is used, and no collections are dropped.

## Security Notes

- **Never print connection strings or credentials** in test output or logs
- The base class validates that the database name begins with `jachai_it_` before running
- Tests refuse to run without a properly configured `MONGODB_TEST_URI`
- Integration tests are opt-in via the `integration` profile

## Troubleshooting

### Tests Skipped

If integration tests are skipped, check:
- `MONGODB_TEST_URI` environment variable is set
- Database name in URI begins with `jachai_it_`
- MongoDB is accessible at the URI

### Connection Errors

If you see connection errors:
- Verify MongoDB is running
- Check network connectivity to MongoDB
- Verify credentials in URI are correct
- Ensure firewall allows connections

### Index Not Found

If index tests fail:
- Ensure `spring.data.mongodb.auto-index-creation=true` in integration profile
- Check that Spring Data MongoDB can create indexes
- Verify user permissions include index creation

## Profile Configuration

The integration profile is defined in `src/test/resources/application-integration.properties`:

```properties
spring.data.mongodb.uri=${MONGODB_TEST_URI}
spring.data.mongodb.auto-index-creation=true
app.jwt.secret=test-only-32-byte-key-for-jachai-integration-tests-123456
```

The test profile (`application-test.properties`) excludes MongoDB auto-configuration for offline unit tests.
