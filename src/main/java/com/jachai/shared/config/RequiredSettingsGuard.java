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
        if (environment.acceptsProfiles(Profiles.of("local"))
                && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("JachAI cannot start with local and prod profiles together.");
        }

        List<Required> missing = new ArrayList<>();
        List<Required> unedited = new ArrayList<>();
        for (Required setting : REQUIRED) {
            String value = resolve(environment, setting);
            if (value == null || value.isBlank()) missing.add(setting);
            else if (looksLikeATemplate(value)) unedited.add(setting);
        }
        if (!unedited.isEmpty()) {
            // A template left as it came is not a missing setting, and reporting it as one sends
            // the reader looking for something that is plainly there. Left unchecked it reaches
            // the driver instead, which rejects it as a wrong password and blames whichever bean
            // happened to need the database first.
            StringBuilder note = new StringBuilder(System.lineSeparator())
                    .append("JachAI cannot start: a setting still holds its placeholder text.")
                    .append(System.lineSeparator());
            for (Required setting : unedited) {
                note.append(System.lineSeparator())
                        .append("  ").append(setting.variable()).append(" has not been filled in")
                        .append(System.lineSeparator())
                        .append("    ").append(setting.purpose()).append(System.lineSeparator());
            }
            note.append(System.lineSeparator())
                    .append("Replace the capitalised placeholder with the real value and run again.")
                    .append(System.lineSeparator());
            throw new IllegalStateException(note.toString());
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
        // Where it looked, and what it found. Without this the reader cannot tell a file
        // that is absent from one that is present but in the wrong folder, or present and
        // missing the line they thought they wrote.
        java.nio.file.Path secrets = java.nio.file.Paths.get("application-local-secret.properties")
                .toAbsolutePath();
        message.append(System.lineSeparator())
                .append("Looked for a local secrets file at:").append(System.lineSeparator())
                .append("  ").append(secrets).append(System.lineSeparator())
                .append("  ").append(java.nio.file.Files.exists(secrets)
                        ? "found, so a setting reported above is missing from it or misspelled"
                        : "not found")
                .append(System.lineSeparator());
        message.append(System.lineSeparator())
                .append("Supply missing production settings in the deployment environment.")
                .append(System.lineSeparator())
                .append("For local JWT_SECRET only, you may copy ")
                .append("application-local-secret.properties.example to").append(System.lineSeparator())
                .append("application-local-secret.properties beside pom.xml and fill it in.")
                .append(System.lineSeparator());
        throw new IllegalStateException(message.toString());
    }

    /** The exact placeholders the example files ship with. */
    private static final List<String> PLACEHOLDERS = List.of(
            "PUT_YOUR_ATLAS_PASSWORD_HERE",
            "replace-with-your-own-random-string",
            "replace-with-a-random-secret",
            "change-this-to-a-long-random-string",
            "USER:PASSWORD");

    /**
     * True when the value still carries text the example file shipped with.
     *
     * <p>Matches literal strings rather than a pattern. A pattern that tries to describe what a
     * placeholder looks like will eventually reject somebody's real password, and a startup guard
     * refusing a correct setting is worse than the failure it was added to prevent. Only the
     * exact text this project ships is treated as unedited.
     */
    private boolean looksLikeATemplate(String value) {
        return PLACEHOLDERS.stream().anyMatch(value::contains);
    }

    /**
     * Reads the effective profile-specific property, rather than a raw environment variable.
     * A leftover Atlas MONGODB_URI must not be mistaken for the local profile's connection.
     *
     * <p>Reading
     * {@code app.jwt.secret} resolves its {@code ${JWT_SECRET}} placeholder, which throws when
     * nothing supplied the variable. That throw is the unreadable failure this guard exists to
     * replace, so here it simply means the setting is absent.
     */
    private String resolve(ConfigurableEnvironment environment, Required setting) {
        try {
            return environment.getProperty(setting.property(), String.class, null);
        } catch (RuntimeException unresolvedPlaceholder) {
            return null;
        }
    }
}
