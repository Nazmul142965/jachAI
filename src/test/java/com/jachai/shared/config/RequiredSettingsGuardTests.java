package com.jachai.shared.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RequiredSettingsGuardTests {
    private final RequiredSettingsGuard guard = new RequiredSettingsGuard();

    @Test
    void localUsesEffectiveMongoPropertyInsteadOfStaleAtlasVariable() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.jwt.secret", "valid-local-test-secret-with-at-least-32-bytes")
                .withProperty("spring.data.mongodb.uri", "mongodb://127.0.0.1:27017")
                .withProperty("MONGODB_URI", "mongodb+srv://USER:PASSWORD@cluster.invalid/");
        environment.setActiveProfiles("local");

        assertDoesNotThrow(() -> guard.postProcessEnvironment(environment, null));
    }

    @Test
    void productionCannotStartWithoutEffectiveMongoUri() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.jwt.secret", "valid-production-test-secret-at-least-32-bytes");
        environment.setActiveProfiles("prod");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> guard.postProcessEnvironment(environment, null));
        assertTrue(failure.getMessage().contains("MONGODB_URI is not set"));
    }

    @Test
    void refusesMixedLocalAndProductionProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local", "prod");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> guard.postProcessEnvironment(environment, null));
        assertTrue(failure.getMessage().contains("local and prod"));
    }
}
