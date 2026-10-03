package com.jachai.ai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GroqAiConfigTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GroqAiConfig.class)
            .withPropertyValues("app.ai.tutor-provider=groq",
                    "app.ai.groq.base-url=https://api.groq.com/openai",
                    "app.ai.groq.model=test-model");

    @Test
    void missingKeyLeavesTheApplicationContextUsableWithoutGroq() {
        runner.withPropertyValues("app.ai.groq.api-key=")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(0, context.getBeansOfType(OpenAiChatModel.class).size());
                });
    }

    @Test
    void configuredKeyCreatesTheGroqModelWithoutCallingTheProvider() {
        runner.withPropertyValues("app.ai.groq.api-key=test-only-key")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(OpenAiChatModel.class).size());
                });
    }
}
