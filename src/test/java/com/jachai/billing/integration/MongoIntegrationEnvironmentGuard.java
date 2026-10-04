package com.jachai.billing.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/** Reject unsafe integration-profile URIs before Spring creates a Mongo client or indexes. */
public final class MongoIntegrationEnvironmentGuard implements EnvironmentPostProcessor, Ordered {

    @Override
    public int getOrder() {
        // Run after Boot has loaded application-integration.properties and activated profiles.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.acceptsProfiles(Profiles.of("integration"))) {
            // Both values come from the environment Spring has already assembled, so this
            // check sees exactly what the context is about to connect to.
            MongoTestUriGuard.requireDedicatedTestDatabase(
                    environment.getProperty("MONGODB_TEST_URI"),
                    environment.getProperty("MONGODB_TEST_DATABASE"));
        }
    }
}
