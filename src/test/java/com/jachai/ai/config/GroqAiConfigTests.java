package com.jachai.ai.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GroqAiConfigTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GroqAiConfig.class)
            .withPropertyValues("app.ai.groq.base-url=https://api.groq.com/openai",
                    "app.ai.groq.model=test-model");

    @Test
    void enabledGroqWithoutKeyFailsStartup() {
        runner.withPropertyValues("app.ai.tutor-provider=groq").run(context ->
                assertNotNull(context.getStartupFailure()));
    }

    @Test
    void disabledGroqDoesNotRequireKey() {
        runner.withPropertyValues("app.ai.tutor-provider=none").run(context ->
                assertNull(context.getStartupFailure()));
    }

    @Test
    void configuredGroqCreatesItsTutorModelWithoutCallingProvider() {
        runner.withPropertyValues("app.ai.tutor-provider=groq",
                "app.ai.groq.api-key=test-only-placeholder").run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(OpenAiChatModel.class));
        });
    }
}
