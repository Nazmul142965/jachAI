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
        String database = connectionString.getDatabase();
        if (database == null || !database.startsWith(REQUIRED_DATABASE_PREFIX)) {
            throw new IllegalArgumentException(
                    "MONGODB_TEST_URI must name a dedicated test database whose name begins with '"
                            + REQUIRED_DATABASE_PREFIX + "'");
        }
        return database;
    }
}
