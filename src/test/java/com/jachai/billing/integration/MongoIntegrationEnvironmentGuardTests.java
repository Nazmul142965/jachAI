package com.jachai.billing.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

class MongoIntegrationEnvironmentGuardTests {
    private final MongoIntegrationEnvironmentGuard guard = new MongoIntegrationEnvironmentGuard();

    @Test
    void testProfileDoesNotRequireLiveMongoUri() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        assertDoesNotThrow(() -> guard.postProcessEnvironment(environment, null));
    }

    @Test
    void integrationProfileRejectsAbsentOrApplicationDatabaseUri() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("integration");
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(environment, null));
        environment.setProperty("MONGODB_TEST_URI", "mongodb://localhost:27017/jachai");
        assertThrows(IllegalArgumentException.class, () -> guard.postProcessEnvironment(environment, null));
    }

    @Test
    void integrationProfileAcceptsOnlyDedicatedDatabase() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("integration");
        environment.setProperty("MONGODB_TEST_URI", "mongodb://localhost:27017/jachai_it_billing");
        assertDoesNotThrow(() -> guard.postProcessEnvironment(environment, null));
    }

    @Test
    void guardIsRegisteredForEverySpringTestContext() {
        assertTrue(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class,
                getClass().getClassLoader()).contains(MongoIntegrationEnvironmentGuard.class.getName()));
    }
}
