package com.jachai.billing.integration;

import com.mongodb.ConnectionString;

/**
 * Validates the opt-in MongoDB test URI before any client, connection or index creation can happen.
 *
 * <p>The guard never opens a MongoDB client or echoes the supplied URI. Parsing an
 * {@code mongodb+srv} URI may perform DNS lookups, so offline tests use ordinary
 * {@code mongodb://} examples. It is called from two places in
 * {@link BaseMongoIntegrationTest}: the Spring {@code @DynamicPropertySource} hook (which runs while
 * the environment is customized, before the context is refreshed) and the JUnit {@code @BeforeAll}
 * hook used by the classes that build their own {@code MongoTemplate}.
 */
final class MongoTestUriGuard {

    static final String REQUIRED_DATABASE_PREFIX = "jachai_it_";

    private MongoTestUriGuard() {
    }

    /**
     * Validates {@code MONGODB_TEST_URI} and returns the dedicated database it names.
     *
     * @param testUri the raw value of {@code MONGODB_TEST_URI}
     * @return the database name, which always begins with {@value #REQUIRED_DATABASE_PREFIX}
     * @throws IllegalStateException    when the variable is unset or blank
     * @throws IllegalArgumentException when the value is not a valid MongoDB connection string or does
     *                                  not name a database beginning with {@value #REQUIRED_DATABASE_PREFIX}
     */
    static String requireDedicatedTestDatabase(String testUri) {
        return requireDedicatedTestDatabase(testUri, System.getenv("MONGODB_TEST_DATABASE"));
    }

    /**
     * Validates the test connection and returns the dedicated database it will use.
     *
     * <p>The database may come from the URI or from {@code MONGODB_TEST_DATABASE}, because a
     * cluster URI is often shared between the application and the suite. Wherever it comes from,
     * it must begin with {@value #REQUIRED_DATABASE_PREFIX}. These tests delete documents, and
     * that prefix is the only thing standing between a misconfigured variable and a real
     * database — which matters more now that the cluster can be a hosted one rather than a
     * throwaway local server.
     *
     * <p>An explicit {@code MONGODB_TEST_DATABASE} wins over a database in the URI, matching how
     * Spring resolves {@code spring.data.mongodb.database}, so the guard checks what will
     * actually be used rather than what the URI happens to say.
     */
    static String requireDedicatedTestDatabase(String testUri, String explicitDatabase) {
        if (testUri == null || testUri.isBlank()) {
            throw new IllegalStateException(
                    "MONGODB_TEST_URI is not set; live MongoDB integration tests must not run");
        }
        ConnectionString connectionString;
        try {
            connectionString = new ConnectionString(testUri);
        } catch (RuntimeException invalid) {
            // The parser failure is deliberately not attached as a cause: its message could contain the
            // URI, and a test guard must never echo credentials.
            throw new IllegalArgumentException("MONGODB_TEST_URI is not a valid MongoDB connection string");
        }
        String database = explicitDatabase != null && !explicitDatabase.isBlank()
                ? explicitDatabase.trim()
                : connectionString.getDatabase();
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException(
                    "No test database was named. Put one in MONGODB_TEST_URI or set "
                            + "MONGODB_TEST_DATABASE, and begin it with '"
                            + REQUIRED_DATABASE_PREFIX + "'");
        }
        if (!database.startsWith(REQUIRED_DATABASE_PREFIX)) {
            throw new IllegalArgumentException(
                    "The MongoDB test database must be a dedicated one whose name begins with '"
                            + REQUIRED_DATABASE_PREFIX + "'. These tests delete documents.");
        }
        return database;
    }
}
