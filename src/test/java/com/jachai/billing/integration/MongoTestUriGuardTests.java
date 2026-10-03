package com.jachai.billing.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Offline proof that the live-test URI guard rejects anything that is not a dedicated
 * {@code jachai_it_} database. This class needs no MongoDB and runs in the normal offline suite.
 */
class MongoTestUriGuardTests {

    @Test
    void rejectsMissingUri() {
        assertThrows(IllegalStateException.class, () -> MongoTestUriGuard.requireDedicatedTestDatabase(null));
        assertThrows(IllegalStateException.class, () -> MongoTestUriGuard.requireDedicatedTestDatabase("   "));
    }

    @Test
    void rejectsUriWithoutDatabase() {
        assertThrows(IllegalArgumentException.class,
                () -> MongoTestUriGuard.requireDedicatedTestDatabase("mongodb://localhost:27017"));
    }

    @Test
    void rejectsApplicationDatabase() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> MongoTestUriGuard.requireDedicatedTestDatabase("mongodb://localhost:27017/jachai"));
        assertEquals(true, String.valueOf(failure.getMessage()).contains("jachai_it_"));
        assertFalse(String.valueOf(failure.getMessage()).contains("/jachai"));
    }

    @Test
    void rejectsMalformedUri() {
        assertThrows(IllegalArgumentException.class,
                () -> MongoTestUriGuard.requireDedicatedTestDatabase("not-a-mongodb-connection-string"));
    }

    @Test
    void acceptsDedicatedTestDatabase() {
        assertEquals("jachai_it_billing",
                MongoTestUriGuard.requireDedicatedTestDatabase("mongodb://localhost:27017/jachai_it_billing"));
        assertEquals("jachai_it_assessment",
                MongoTestUriGuard.requireDedicatedTestDatabase(
                        "mongodb+srv://user:pass@cluster0.example.net/jachai_it_assessment?retryWrites=true"));
    }

    @Test
    void rejectedUriIsNeverEchoedInTheFailure() {
        String credentialsWithWrongDatabase = "mongodb://appuser:sup3rsecret@localhost:27017/production";
        IllegalArgumentException wrongDatabase = assertThrows(IllegalArgumentException.class,
                () -> MongoTestUriGuard.requireDedicatedTestDatabase(credentialsWithWrongDatabase));
        assertFalse(String.valueOf(wrongDatabase.getMessage()).contains("sup3rsecret"));

        String malformedWithCredentials = "not-a-uri:sup3rsecret@localhost/production";
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                () -> MongoTestUriGuard.requireDedicatedTestDatabase(malformedWithCredentials));
        assertFalse(String.valueOf(malformed.getMessage()).contains("sup3rsecret"));
        assertNull(malformed.getCause(),
                "The parser failure must not be attached as a cause, because its message can contain the URI");
    }
}
