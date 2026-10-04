package com.jachai.shared.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * Fails immediately, and in plain words, when a setting the application cannot start without is
 * missing.
 *
 * <p>Without this the first thing to need the setting is whatever bean Spring happens to build
 * first, so a missing JWT_SECRET surfaced as a wall of stack trace about certificateController
 * and emailPayloadCipher, with the real cause on the hundredth line. The reader has no way to
 * know that a certificate controller has anything to do with a signing key.
 *
 * <p>This runs while the environment is being prepared, before any bean exists, so the message is
 * the first thing printed and nothing else has failed yet to distract from it.
 *
 * <p>It reports every missing setting at once rather than the first one. Finding out about three
 * missing variables one restart at a time is its own kind of unpleasant.
 *
 * <p>Values are never printed, only whether each one is present.
 */
public class RequiredSettingsGuard implements EnvironmentPostProcessor, Ordered {

    /** Property, the environment variable that supplies it, and what it is for. */
    private record Required(String property, String variable, String purpose) { }

    private static final List<Required> REQUIRED = List.of(
            new Required("app.jwt.secret", "JWT_SECRET",
                    "signs access tokens, and encrypts queued email payloads by default"),
            new Required("spring.data.mongodb.uri", "MONGODB_URI",
                    "the database cluster to connect to"));

    @Override
    public int getOrder() {
        // After Boot has loaded application.properties and applied profiles, so a value set
        // there counts as present.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Tests supply these differently: some set the property directly, and the Mongo
        // integration classes register the URI through @DynamicPropertySource, which runs after
        // this stage. Checking them here would fail a suite that is correctly configured, and
        // the guard's purpose is to help a person starting the application, not a test context.
        if (environment.acceptsProfiles(Profiles.of("test", "integration"))) return;

        List<Required> missing = new ArrayList<>();
        for (Required setting : REQUIRED) {
            String value = resolve(environment, setting);
            if (value == null || value.isBlank()) missing.add(setting);
        }
        if (missing.isEmpty()) return;

        StringBuilder message = new StringBuilder();
        message.append(System.lineSeparator())
                .append("JachAI cannot start: ")
                .append(missing.size() == 1 ? "a required setting is missing." : "required settings are missing.")
                .append(System.lineSeparator());
        for (Required setting : missing) {
            message.append(System.lineSeparator())
                    .append("  ").append(setting.variable()).append(" is not set")
                    .append(System.lineSeparator())
                    .append("    ").append(setting.purpose())
                    .append(System.lineSeparator());
        }
        message.append(System.lineSeparator())
                .append("Two ways to supply them:").append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("  1. Easiest for development. Copy ")
                .append("application-local-secret.properties.example to").append(System.lineSeparator())
                .append("     application-local-secret.properties beside pom.xml and fill it in.")
                .append(System.lineSeparator())
                .append("     It is read on every run and is already in .gitignore.")
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("  2. As environment variables, in the shell or run configuration that")
                .append(System.lineSeparator())
                .append("     starts the application. This is how deployment supplies them.")
                .append(System.lineSeparator());
        throw new IllegalStateException(message.toString());
    }

    /**
     * Reads the environment variable first, because that is what a person actually sets.
     *
     * <p>The property is only consulted as a fallback, and inside a try: reading
     * {@code app.jwt.secret} resolves its {@code ${JWT_SECRET}} placeholder, which throws when
     * nothing supplied the variable. That throw is the unreadable failure this guard exists to
     * replace, so here it simply means the setting is absent.
     */
    private String resolve(ConfigurableEnvironment environment, Required setting) {
        String fromVariable = environment.getProperty(setting.variable(), String.class, null);
        if (fromVariable != null && !fromVariable.isBlank()) return fromVariable;
        try {
            return environment.getProperty(setting.property(), String.class, null);
        } catch (RuntimeException unresolvedPlaceholder) {
            return null;
        }
    }
}
