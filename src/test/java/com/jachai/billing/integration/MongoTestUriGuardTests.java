package com.jachai.billing.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Offline proof that the live-test URI guard rejects anything that is not a dedicated
 * {@code jachai_it_} database. This class needs no MongoDB and runs in the normal offline suite.
 */
class MongoTestUriGuardTests {

    @Test
    void rejectsMissingUri() {
        assertThrows(IllegalStateException.class, () -> MongoTestUriGuard.requireDedicatedTestDatabase(null, null));
        assertThrows(IllegalStateException.class, () -> MongoTestUriGuard.requireDedicatedTestDatabase("   ", null));
    }

    @Test
    void rejectsUriWithoutDatabase() {
        assertThrows(IllegalArgumentException.class,
                () -> MongoTestUriGuard.requireDedicatedTestDatabase("mongodb://localhost:27017", null));
    }

    @Test
    void rejectsApplicationDatabase() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> MongoTestUriGuard.requireDedicatedTestDatabase("mongodb://localhost:27017/jachai", null));
        assertEquals(true, String.valueOf(failure.getMessage()).contains("jachai_it_"));
        assertFalse(String.valueOf(failure.getMessage()).contains("/jachai"));
    }

    @Test
    void rejectsMalformedUri() {
        assertThrows(IllegalArgumentException.class,
                () -> MongoTestUriGuard.requireDedicatedTestDatabase("not-a-mongodb-connection-string", null));
    }

    @Test
    void acceptsDedicatedTestDatabase() {
        assertEquals("jachai_it_billing",
                MongoTestUriGuard.requireDedicatedTestDatabase("mongodb://localhost:27017/jachai_it_billing", null));
        assertEquals("jachai_it_assessment",
                MongoTestUriGuard.requireDedicatedTestDatabase("mongodb://localhost:27017/jachai_it_assessment?retryWrites=true", null));
    }

    @Test
    void rejectedUriIsNeverEchoedInTheFailure() {
        String credentialsWithWrongDatabase = "mongodb://appuser:sup3rsecret@localhost:27017/production";
        IllegalArgumentException wrongDatabase = assertThrows(IllegalArgumentException.class,
                () -> MongoTestUriGuard.requireDedicatedTestDatabase(credentialsWithWrongDatabase, null));
        assertFalse(String.valueOf(wrongDatabase.getMessage()).contains("sup3rsecret"));

        String malformedWithCredentials = "not-a-uri:sup3rsecret@localhost/production";
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                () -> MongoTestUriGuard.requireDedicatedTestDatabase(malformedWithCredentials, null));
        assertFalse(String.valueOf(malformed.getMessage()).contains("sup3rsecret"));
        assertNull(malformed.getCause(),
                "The parser failure must not be attached as a cause, because its message can contain the URI");
    }

    @Test
    void acceptsADedicatedDatabaseNamedOutsideTheUri() {
        // A cluster URI shared with the application names no database of its own.
        assertEquals("jachai_it_suite", MongoTestUriGuard.requireDedicatedTestDatabase(
                "mongodb://example-host:27017", "jachai_it_suite"));
    }

    @Test
    void anExplicitDatabaseWinsOverOneInTheUri() {
        // Matches how Spring resolves spring.data.mongodb.database, so the guard checks the
        // database that will actually be used.
        assertEquals("jachai_it_suite", MongoTestUriGuard.requireDedicatedTestDatabase(
                "mongodb://example-host:27017/jachai_it_other", "jachai_it_suite"));
    }

    @Test
    void refusesAnExplicitDatabaseWithoutTheDedicatedPrefix() {
        // The case that matters most now the cluster may be a hosted one: a real database name
        // supplied by mistake must never be accepted, because these tests delete documents.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> MongoTestUriGuard.requireDedicatedTestDatabase(
                        "mongodb://example-host:27017", "jachai"));
        assertTrue(refused.getMessage().contains("jachai_it_"));
        assertFalse(refused.getMessage().contains("example-host"), "the guard must not echo the URI");
    }

    @Test
    void refusesWhenNoDatabaseIsNamedAnywhere() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> MongoTestUriGuard.requireDedicatedTestDatabase(
                        "mongodb://example-host:27017", null));
        assertTrue(refused.getMessage().contains("MONGODB_TEST_DATABASE"));
    }

    @Test
    void treatsABlankExplicitDatabaseAsAbsentAndFallsBackToTheUri() {
        assertEquals("jachai_it_fallback", MongoTestUriGuard.requireDedicatedTestDatabase(
                "mongodb://example-host:27017/jachai_it_fallback", "   "));
    }
}
